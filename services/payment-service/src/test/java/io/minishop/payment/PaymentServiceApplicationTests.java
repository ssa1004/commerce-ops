package io.minishop.payment;

import io.minishop.payment.domain.PaymentStatus;
import io.minishop.payment.kafka.PaymentEventPublisher;
import io.minishop.payment.service.PgClient;
import io.minishop.payment.web.dto.CreatePaymentRequest;
import io.minishop.payment.web.dto.PaymentResponse;
import io.minishop.payment.web.dto.PgChargeResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class PaymentServiceApplicationTests {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@MockitoBean
	PgClient pgClient;

	// 테스트에서 실제 Kafka 브로커가 없으므로 publisher를 mock으로 대체.
	// publisher가 호출되는지 자체는 별도 테스트(여기서는 검증 안 함).
	@MockitoBean
	PaymentEventPublisher eventPublisher;

	@Autowired
	TestRestTemplate http;

	@BeforeEach
	void setUp() {
		when(pgClient.charge(any())).thenReturn(PgChargeResponse.ok("pg-test-ref"));
	}

	@Test
	void processesPaymentSuccessfully() {
		ResponseEntity<PaymentResponse> response = http.postForEntity(
				"/payments",
				new CreatePaymentRequest(100L, 42L, new BigDecimal("39880.00")),
				PaymentResponse.class
		);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().status()).isEqualTo(PaymentStatus.SUCCESS);
		assertThat(response.getBody().externalRef()).isEqualTo("pg-test-ref");
		assertThat(response.getBody().attempts()).isEqualTo(1);

		Long id = response.getBody().id();
		ResponseEntity<PaymentResponse> fetched = http.getForEntity("/payments/{id}", PaymentResponse.class, id);
		assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(fetched.getBody().id()).isEqualTo(id);
	}

	@Test
	void marksPaymentFailedOnPgFailure() {
		when(pgClient.charge(any())).thenThrow(new PgClient.PgFailureException("PG returned 500"));

		ResponseEntity<PaymentResponse> response = http.postForEntity(
				"/payments",
				new CreatePaymentRequest(101L, 42L, new BigDecimal("9990.00")),
				PaymentResponse.class
		);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
		assertThat(response.getBody().status()).isEqualTo(PaymentStatus.FAILED);
		assertThat(response.getBody().failureReason()).contains("PG returned 500");
	}

	@Test
	void prometheusEndpointExposesJvmAndHttpMetrics() {
		// http_server_requests_seconds 는 첫 요청이 끝난 뒤에 등록되므로 한 번 두드려서 워밍업.
		http.getForEntity("/actuator/health", String.class);

		// JvmMetricsAutoConfiguration 의 MeterBinder 들은 ApplicationStartedEvent 직후에 등록되므로
		// 부팅 직후 첫 스크레이프가 그 등록보다 먼저 실행되면 jvm_memory_used_bytes 가 누락될 수 있다.
		await().atMost(Duration.ofSeconds(5))
				.pollInterval(Duration.ofMillis(100))
				.untilAsserted(() -> {
					ResponseEntity<String> metrics = http.getForEntity("/actuator/prometheus", String.class);
					assertThat(metrics.getStatusCode()).isEqualTo(HttpStatus.OK);
					assertThat(metrics.getBody())
							.contains("jvm_memory_used_bytes")
							.contains("http_server_requests_seconds");
				});
	}
}
