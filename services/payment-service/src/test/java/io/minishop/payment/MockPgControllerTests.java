package io.minishop.payment;

import io.minishop.payment.web.dto.PgChargeRequest;
import io.minishop.payment.web.dto.PgChargeResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * mock-pg endpoint 자체의 행동을 검증한다.
 * failure/timeout rate를 0으로 두면 항상 ok 응답이 와야 한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = {
		"mini-shop.mock-pg.latency-mean-ms=1",
		"mini-shop.mock-pg.latency-stddev-ms=0",
		"mini-shop.mock-pg.failure-rate=0",
		"mini-shop.mock-pg.timeout-rate=0"
})
class MockPgControllerTests {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired
	TestRestTemplate http;

	@Test
	void chargeReturnsOkWhenFailureRateZero() {
		ResponseEntity<PgChargeResponse> res = http.postForEntity(
				"/mock-pg/charge",
				new PgChargeRequest(1L, new BigDecimal("100.00")),
				PgChargeResponse.class
		);
		assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(res.getBody()).isNotNull();
		assertThat(res.getBody().success()).isTrue();
		assertThat(res.getBody().reference()).startsWith("pg-");
	}
}
