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

import static org.assertj.core.api.Assertions.assertThat;

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

		// initial stock from V2__seed_inventory.sql is 100
		ResponseEntity<InventoryResponse> initial = http.getForEntity(
				"/inventories/{productId}", InventoryResponse.class, productId);
		assertThat(initial.getStatusCode()).isEqualTo(HttpStatus.OK);
		int before = initial.getBody().availableQuantity();

		// 1st reserve — CREATED
		ResponseEntity<ReservationResponse> r1 = http.postForEntity(
				"/inventories/reserve",
				new ReserveRequest(productId, orderId, 5),
				ReservationResponse.class
		);
		assertThat(r1.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(r1.getBody().idempotent()).isFalse();

		// 2nd reserve, same key — idempotent OK, NOT a second decrement
		ResponseEntity<ReservationResponse> r2 = http.postForEntity(
				"/inventories/reserve",
				new ReserveRequest(productId, orderId, 5),
				ReservationResponse.class
		);
		assertThat(r2.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(r2.getBody().idempotent()).isTrue();
		assertThat(r2.getBody().id()).isEqualTo(r1.getBody().id());

		// stock decreased by exactly 5
		ResponseEntity<InventoryResponse> midway = http.getForEntity(
				"/inventories/{productId}", InventoryResponse.class, productId);
		assertThat(midway.getBody().availableQuantity()).isEqualTo(before - 5);

		// release once — restores stock
		ResponseEntity<ReservationResponse> rel1 = http.postForEntity(
				"/inventories/release",
				new ReleaseRequest(productId, orderId),
				ReservationResponse.class
		);
		assertThat(rel1.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(rel1.getBody().idempotent()).isFalse();

		// release twice — idempotent, stock not double-restored
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
		Long productId = 1003L; // seeded with 25
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
		// Trigger a reserve to populate inventory.lock.acquire timer
		http.postForEntity("/inventories/reserve",
				new ReserveRequest(1002L, 9999L, 1), ReservationResponse.class);

		ResponseEntity<String> metrics = http.getForEntity("/actuator/prometheus", String.class);
		assertThat(metrics.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(metrics.getBody()).contains("jvm_memory_used_bytes");
		assertThat(metrics.getBody()).contains("http_server_requests_seconds");
		assertThat(metrics.getBody()).contains("inventory_lock_acquire_seconds");
	}
}
