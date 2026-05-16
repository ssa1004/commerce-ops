package io.minishop.order.service

import io.minishop.order.concurrency.LimitExceededException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient

@Component
class InventoryClient(
    @Qualifier("inventoryRestClient") private val client: RestClient,
) {

    fun reserve(productId: Long, orderId: Long, quantity: Int): ReservationResult {
        try {
            return client.post()
                .uri("/inventories/reserve")
                .body(ReserveRequest(productId, orderId, quantity))
                .retrieve()
                .onStatus(
                    { s: HttpStatusCode -> s.value() == HttpStatus.CONFLICT.value() },
                    { _, _ -> throw OutOfStockException("product $productId out of stock") },
                )
                .onStatus(
                    { s: HttpStatusCode -> s.isError && s.value() != HttpStatus.CONFLICT.value() },
                    { _, res -> throw InventoryInfraException("inventory-service returned ${res.statusCode}") },
                )
                .body(ReservationResult::class.java)
                ?: throw InventoryInfraException("inventory-service returned empty body")
        } catch (e: LimitExceededException) {
            // adaptive limiter 가 cascade 차단으로 즉시 거절 — 진짜 inventory 가 아니라 *우리 쪽이*
            // 한도 초과 판단을 한 것. 여기서 그대로 throw 하면 OrderService 가 outcome 매핑.
            log.warn(
                "inventory call rejected by adaptive limiter (limit={}): {}",
                e.currentLimit, e.message,
            )
            throw e
        } catch (e: ResourceAccessException) {
            log.warn("inventory-service unreachable: {}", e.message)
            throw InventoryInfraException("inventory-service unreachable: ${e.message}", e)
        }
    }

    /**
     * Idempotent — 동일 (orderId, productId)로 여러 번 호출해도 안전.
     * 보상 호출이므로 인프라 장애 시에도 throw하지 않고 로그만 남긴다 (상위에서 이미 실패 처리 중).
     *
     * release 가 limiter 한도를 만나도 마찬가지 — 보상은 *어차피 best-effort* 라 즉시 거절을 받아
     * 도 throw 하지 않고 로그만. reconciliation 잡 (ADR-011) 이 사후에 부정합을 잡는다.
     */
    fun release(productId: Long, orderId: Long) {
        try {
            client.post()
                .uri("/inventories/release")
                .body(ReleaseRequest(productId, orderId))
                .retrieve()
                .onStatus({ s: HttpStatusCode -> s.isError }, { _, res ->
                    log.warn(
                        "inventory release returned {} for product={} order={} — possible orphan reservation",
                        res.statusCode, productId, orderId,
                    )
                    throw InventoryInfraException("release failed: ${res.statusCode}")
                })
                .toBodilessEntity()
        } catch (e: Exception) {
            log.error(
                "inventory release threw for product={} order={}: {} — possible orphan reservation",
                productId, orderId, e.message,
            )
        }
    }

    @JvmRecord
    data class ReserveRequest(val productId: Long, val orderId: Long, val quantity: Int)

    @JvmRecord
    data class ReleaseRequest(val productId: Long, val orderId: Long)

    @JvmRecord
    data class ReservationResult(
        val id: Long?,
        val productId: Long?,
        val orderId: Long?,
        val quantity: Int?,
        val status: String?,
        val idempotent: Boolean,
    )

    class OutOfStockException(message: String) : RuntimeException(message)

    class InventoryInfraException : RuntimeException {
        constructor(message: String) : super(message)
        constructor(message: String, cause: Throwable) : super(message, cause)
    }

    companion object {
        private val log = LoggerFactory.getLogger(InventoryClient::class.java)
    }
}
