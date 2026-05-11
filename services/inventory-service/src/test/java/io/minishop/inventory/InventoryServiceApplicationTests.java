package io.minishop.inventory;

import io.minishop.inventory.kafka.InventoryEventPublisher;
import io.minishop.inventory.web.dto.InventoryResponse;
import io.minishop.inventory.web.dto.ReleaseRequest;
import io.minishop.inventory.web.dto.ReservationResponse;
import io.minishop.inventory.web.dto.ReserveRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class InventoryServiceApplicationTests {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Container
	static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

	@DynamicPropertySource
	static void redisProps(DynamicPropertyRegistry registry) {
		registry.add("spring.data.redis.host", redis::getHost);
		registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
	}

	// 테스트에서 실제 Kafka가 없으므로 mock으로 교체. 발행 동작 자체는 별도 테스트 대상.
	@MockitoBean
	InventoryEventPublisher eventPublisher;

	@Autowired
	TestRestTemplate http;

	@Test
	void reservesAndReleasesWithIdempotency() {
		Long productId = 1001L;
		Long orderId = 9001L;

		// 초기 재고는 V2__seed_inventory.sql 의 시드 — productId=1001 은 100 으로 시작.
		ResponseEntity<InventoryResponse> initial = http.getForEntity(
				"/inventories/{productId}", InventoryResponse.class, productId);
		assertThat(initial.getStatusCode()).isEqualTo(HttpStatus.OK);
		int before = initial.getBody().availableQuantity();

		// 첫 reserve — 새 reservation 이라 201 CREATED.
		ResponseEntity<ReservationResponse> r1 = http.postForEntity(
				"/inventories/reserve",
				new ReserveRequest(productId, orderId, 5),
				ReservationResponse.class
		);
		assertThat(r1.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(r1.getBody().idempotent()).isFalse();

		// 같은 키로 두 번째 reserve — 멱등 200 OK, 재고는 추가 차감되지 않아야.
		ResponseEntity<ReservationResponse> r2 = http.postForEntity(
				"/inventories/reserve",
				new ReserveRequest(productId, orderId, 5),
				ReservationResponse.class
		);
		assertThat(r2.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(r2.getBody().idempotent()).isTrue();
		assertThat(r2.getBody().id()).isEqualTo(r1.getBody().id());

		// 재고는 정확히 5 만 차감되어야.
		ResponseEntity<InventoryResponse> midway = http.getForEntity(
				"/inventories/{productId}", InventoryResponse.class, productId);
		assertThat(midway.getBody().availableQuantity()).isEqualTo(before - 5);

		// 첫 release — 재고 복구.
		ResponseEntity<ReservationResponse> rel1 = http.postForEntity(
				"/inventories/release",
				new ReleaseRequest(productId, orderId),
				ReservationResponse.class
		);
		assertThat(rel1.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(rel1.getBody().idempotent()).isFalse();

		// 같은 키로 두 번째 release — 멱등, 재고가 두 번 복구되지 않아야.
		ResponseEntity<ReservationResponse> rel2 = http.postForEntity(
				"/inventories/release",
				new ReleaseRequest(productId, orderId),
				ReservationResponse.class
		);
		assertThat(rel2.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(rel2.getBody().idempotent()).isTrue();

		ResponseEntity<InventoryResponse> after = http.getForEntity(
				"/inventories/{productId}", InventoryResponse.class, productId);
		assertThat(after.getBody().availableQuantity()).isEqualTo(before);
	}

	@Test
	void rejectsReserveWhenOutOfStock() {
		Long productId = 1003L; // V2 시드: 25
		Long orderId = 9002L;

		ResponseEntity<String> response = http.postForEntity(
				"/inventories/reserve",
				new ReserveRequest(productId, orderId, 1000),
				String.class
		);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(response.getBody()).contains("OUT_OF_STOCK");
	}

	@Test
	void prometheusEndpointExposesJvmAndHttpAndLockMetrics() {
		// inventory.lock.acquire timer 가 비어있지 않게 reserve 한 번 발사.
		http.postForEntity("/inventories/reserve",
				new ReserveRequest(1002L, 9999L, 1), ReservationResponse.class);

		// JvmMetricsAutoConfiguration 의 MeterBinder 들은 ApplicationStartedEvent 직후에 등록되므로
		// 부팅 직후 첫 스크레이프가 그 등록보다 먼저 실행되면 jvm_memory_used_bytes 가 누락될 수 있다.
		await().atMost(Duration.ofSeconds(5))
				.pollInterval(Duration.ofMillis(100))
				.untilAsserted(() -> {
					ResponseEntity<String> metrics = http.getForEntity("/actuator/prometheus", String.class);
					assertThat(metrics.getStatusCode()).isEqualTo(HttpStatus.OK);
					assertThat(metrics.getBody())
							.contains("jvm_memory_used_bytes")
							.contains("http_server_requests_seconds")
							.contains("inventory_lock_acquire_seconds");
				});
	}
}
