package io.minishop.order.service;

import io.minishop.order.concurrency.LimitExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

@Component
public class InventoryClient {

    private static final Logger log = LoggerFactory.getLogger(InventoryClient.class);

    private final RestClient client;

    public InventoryClient(@Qualifier("inventoryRestClient") RestClient client) {
        this.client = client;
    }

    public ReservationResult reserve(Long productId, Long orderId, Integer quantity) {
        try {
            return client.post()
                    .uri("/inventories/reserve")
                    .body(new ReserveRequest(productId, orderId, quantity))
                    .retrieve()
                    .onStatus(
                            (HttpStatusCode s) -> s.value() == HttpStatus.CONFLICT.value(),
                            (req, res) -> {
                                throw new OutOfStockException("product " + productId + " out of stock");
                            }
                    )
                    .onStatus(
                            (HttpStatusCode s) -> s.isError() && s.value() != HttpStatus.CONFLICT.value(),
                            (req, res) -> {
                                throw new InventoryInfraException("inventory-service returned " + res.getStatusCode());
                            }
                    )
                    .body(ReservationResult.class);
        } catch (LimitExceededException e) {
            // adaptive limiter 가 cascade 차단으로 즉시 거절 — 진짜 inventory 가 아니라 *우리 쪽이*
            // 한도 초과 판단을 한 것. 여기서 그대로 throw 하면 OrderService 가 outcome 매핑.
            log.warn("inventory call rejected by adaptive limiter (limit={}): {}",
                    e.getCurrentLimit(), e.getMessage());
            throw e;
        } catch (ResourceAccessException e) {
            log.warn("inventory-service unreachable: {}", e.getMessage());
            throw new InventoryInfraException("inventory-service unreachable: " + e.getMessage(), e);
        }
    }

    /**
     * Idempotent — 동일 (orderId, productId)로 여러 번 호출해도 안전.
     * 보상 호출이므로 인프라 장애 시에도 throw하지 않고 로그만 남긴다 (상위에서 이미 실패 처리 중).
     *
     * <p>release 가 limiter 한도를 만나도 마찬가지 — 보상은 *어차피 best-effort* 라 즉시 거절을 받아
     * 도 throw 하지 않고 로그만. reconciliation 잡 (ADR-011) 이 사후에 부정합을 잡는다.
     */
    public void release(Long productId, Long orderId) {
        try {
            client.post()
                    .uri("/inventories/release")
                    .body(new ReleaseRequest(productId, orderId))
                    .retrieve()
                    .onStatus((HttpStatusCode s) -> s.isError(), (req, res) -> {
                        log.warn("inventory release returned {} for product={} order={} — possible orphan reservation",
                                res.getStatusCode(), productId, orderId);
                        throw new InventoryInfraException("release failed: " + res.getStatusCode());
                    })
                    .toBodilessEntity();
        } catch (Exception e) {
            log.error("inventory release threw for product={} order={}: {} — possible orphan reservation",
                    productId, orderId, e.getMessage());
        }
    }

    public record ReserveRequest(Long productId, Long orderId, Integer quantity) {}
    public record ReleaseRequest(Long productId, Long orderId) {}
    public record ReservationResult(Long id, Long productId, Long orderId, Integer quantity, String status, boolean idempotent) {}

    public static class OutOfStockException extends RuntimeException {
        public OutOfStockException(String message) { super(message); }
    }

    public static class InventoryInfraException extends RuntimeException {
        public InventoryInfraException(String message) { super(message); }
        public InventoryInfraException(String message, Throwable cause) { super(message, cause); }
    }
}
