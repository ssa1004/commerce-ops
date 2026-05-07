package io.minishop.order;

import io.minishop.order.web.dto.CreateOrderItemRequest;
import io.minishop.order.web.dto.CreateOrderRequest;
import io.minishop.order.web.dto.OrderResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class OrderServiceApplicationTests {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired
	TestRestTemplate http;

	@Test
	void createsAndReadsOrder() {
		CreateOrderRequest req = new CreateOrderRequest(
				42L,
				List.of(
						new CreateOrderItemRequest(1001L, 2, new BigDecimal("9990.00")),
						new CreateOrderItemRequest(1002L, 1, new BigDecimal("19900.00"))
				)
		);

		ResponseEntity<OrderResponse> created = http.postForEntity("/orders", req, OrderResponse.class);

		assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(created.getBody()).isNotNull();
		assertThat(created.getBody().id()).isNotNull();
		assertThat(created.getBody().totalAmount()).isEqualByComparingTo("39880.00");
		assertThat(created.getBody().items()).hasSize(2);

		Long id = created.getBody().id();
		ResponseEntity<OrderResponse> fetched = http.getForEntity("/orders/{id}", OrderResponse.class, id);
		assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(fetched.getBody().id()).isEqualTo(id);
		assertThat(fetched.getBody().items()).hasSize(2);
	}

	@Test
	void prometheusEndpointExposesJvmAndHttpMetrics() {
		http.getForEntity("/actuator/health", String.class);
		ResponseEntity<String> metrics = http.getForEntity("/actuator/prometheus", String.class);
		assertThat(metrics.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(metrics.getBody()).contains("jvm_memory_used_bytes");
		assertThat(metrics.getBody()).contains("http_server_requests_seconds");
	}

}
