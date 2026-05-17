package io.minishop.order;

import io.micrometer.core.instrument.MeterRegistry;
import io.minishop.order.domain.OrderStatus;
import io.minishop.order.service.InventoryClient;
import io.minishop.order.service.PaymentClient;
import io.minishop.order.web.dto.CreateOrderItemRequest;
import io.minishop.order.web.dto.CreateOrderRequest;
import io.minishop.order.web.dto.OrderResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.awaitility.Awaitility.await;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class OrderServiceApplicationTests {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@MockitoBean
	InventoryClient inventoryClient;

	@MockitoBean
	PaymentClient paymentClient;

	@Autowired
	TestRestTemplate http;

	@Autowired
	MeterRegistry meterRegistry;

	@BeforeEach
	void setUp() {
		when(inventoryClient.reserve(anyLong(), anyLong(), anyInt())).thenReturn(
				new InventoryClient.ReservationResult(1L, 1001L, 1L, 1, "RESERVED", false)
		);
		when(paymentClient.charge(anyLong(), anyLong(), any())).thenReturn(
				new PaymentClient.PaymentResult(1L, 1L, "SUCCESS", "pg-test", null)
		);
	}

	@Test
	void happyPath_paid_201() {
		CreateOrderRequest req = new CreateOrderRequest(
				42L,
				List.of(
						new CreateOrderItemRequest(1001L, 2, new BigDecimal("9990.00")),
						new CreateOrderItemRequest(1002L, 1, new BigDecimal("19900.00"))
				)
		);

		ResponseEntity<OrderResponse> created = http.postForEntity("/orders", req, OrderResponse.class);
		assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(created.getBody().status()).isEqualTo(OrderStatus.PAID);
		assertThat(created.getBody().totalAmount()).isEqualByComparingTo("39880.00");

		verify(inventoryClient, times(2)).reserve(anyLong(), anyLong(), anyInt());
		verify(paymentClient, times(1)).charge(anyLong(), anyLong(), any());
		verify(inventoryClient, never()).release(anyLong(), anyLong());
	}

	@Test
	void outOfStock_compensatesAndReturns409() {
		when(inventoryClient.reserve(eq(1001L), anyLong(), anyInt())).thenReturn(
				new InventoryClient.ReservationResult(1L, 1001L, 1L, 1, "RESERVED", false)
		);
		when(inventoryClient.reserve(eq(1002L), anyLong(), anyInt())).thenThrow(
				new InventoryClient.OutOfStockException("product 1002 out of stock")
		);

		CreateOrderRequest req = new CreateOrderRequest(
				42L,
				List.of(
						new CreateOrderItemRequest(1001L, 2, new BigDecimal("9990.00")),
						new CreateOrderItemRequest(1002L, 1, new BigDecimal("19900.00"))
				)
		);

		ResponseEntity<OrderResponse> response = http.postForEntity("/orders", req, OrderResponse.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(response.getHeaders().getFirst("X-Order-Outcome")).isEqualTo("OUT_OF_STOCK");
		assertThat(response.getBody().status()).isEqualTo(OrderStatus.FAILED);

		// 보상: 첫 번째 아이템(1001)만 release. 두 번째는 reserve 실패라 release 안 함.
		verify(inventoryClient, times(1)).release(eq(1001L), anyLong());
		verify(inventoryClient, never()).release(eq(1002L), anyLong());
		verify(paymentClient, never()).charge(anyLong(), anyLong(), any());
	}

	@Test
	void paymentDeclined_compensatesAndReturns402() {
		when(paymentClient.charge(anyLong(), anyLong(), any())).thenReturn(
				new PaymentClient.PaymentResult(1L, 1L, "FAILED", null, "card declined")
		);

		CreateOrderRequest req = new CreateOrderRequest(
				42L,
				List.of(new CreateOrderItemRequest(1001L, 1, new BigDecimal("9990.00")))
		);

		ResponseEntity<OrderResponse> response = http.postForEntity("/orders", req, OrderResponse.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
		assertThat(response.getHeaders().getFirst("X-Order-Outcome")).isEqualTo("PAYMENT_DECLINED");
		assertThat(response.getBody().status()).isEqualTo(OrderStatus.FAILED);

		verify(inventoryClient, atLeastOnce()).release(anyLong(), anyLong());
	}

	@Test
	void naiveListEndpoint_triggersNPlusOneDetector() {
		// 5개 주문 생성 (각 1 item) — 모두 같은 SQL 패턴(SELECT items WHERE order_id = ?)이 반복될 조건
		double before = meterRegistry.counter("n_plus_one_total").count();
		for (int i = 0; i < 5; i++) {
			http.postForEntity("/orders",
					new CreateOrderRequest((long) i,
							List.of(new CreateOrderItemRequest(1001L, 1, new BigDecimal("9990.00")))),
					OrderResponse.class);
		}

		// 의도적 N+1 경로: GET /orders → naive findAll → items lazy load
		ResponseEntity<List<OrderResponse>> resp = http.exchange(
				"/orders?size=10", HttpMethod.GET, null,
				new ParameterizedTypeReference<>() {}
		);
		assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(resp.getBody()).hasSizeGreaterThanOrEqualTo(5);

		double after = meterRegistry.counter("n_plus_one_total").count();
		// 같은 정규화 SQL이 5번 이상 반복 → 임계(기본 5)에 도달하면 카운터가 한 번 증가
		assertThat(after).isGreaterThan(before);
	}

	@Test
	void prometheusEndpointExposesJvmHttpAndOrchestrationMetrics() {
		http.postForEntity("/orders",
				new CreateOrderRequest(42L,
						List.of(new CreateOrderItemRequest(1001L, 1, new BigDecimal("9990.00")))),
				OrderResponse.class);

		// 부팅 직후 경합 안정화 — Spring Boot 3.5 + Micrometer 1.14 환경에서 JvmMetricsAutoConfiguration
		// 의 MeterBinder (jvm_memory_used_bytes 등) 는 ApplicationStartedEvent 직후에 등록된다.
		// SpringBootTest RANDOM_PORT 부팅이 끝나고 첫 /actuator/prometheus 스크레이프가 그 등록보다
		// 먼저 실행되면 본문에 JVM 메트릭이 빠져 있어 contains 어설션이 깨지는 경합이 있었다.
		// 한 번 호출로 단정짓는 대신 5초 동안 100ms 간격으로 폴링해 메트릭이 등록되는 시점을 기다린다.
		await().atMost(Duration.ofSeconds(5))
				.pollInterval(Duration.ofMillis(100))
				.untilAsserted(() -> {
					ResponseEntity<String> metrics = http.getForEntity("/actuator/prometheus", String.class);
					assertThat(metrics.getStatusCode()).isEqualTo(HttpStatus.OK);
					assertThat(metrics.getBody())
							.contains("jvm_memory_used_bytes")
							.contains("http_server_requests_seconds")
							.contains("order_orchestration_seconds");
				});
	}
}
