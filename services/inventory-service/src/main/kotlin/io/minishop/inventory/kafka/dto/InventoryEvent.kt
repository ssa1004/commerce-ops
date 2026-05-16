package io.minishop.inventory.kafka.dto

import com.fasterxml.jackson.annotation.JsonInclude
import io.minishop.inventory.domain.InventoryReservation
import io.minishop.inventory.domain.ReservationStatus
import java.time.Instant

/**
 * Kafka 토픽 `inventory.events` 의 페이로드 — JSON 직렬화 형태가 외부 계약이므로
 * 필드 순서 / 이름 변경 금지. Java record → `@JvmRecord data class` 변환.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JvmRecord
data class InventoryEvent(
    val type: String, // InventoryReserved / InventoryReleased
    val reservationId: Long?,
    val productId: Long?,
    val orderId: Long?,
    val quantity: Int?,
    val status: ReservationStatus?,
    val idempotent: Boolean,
    val occurredAt: Instant,
) {

    companion object {
        const val TYPE_RESERVED: String = "InventoryReserved"
        const val TYPE_RELEASED: String = "InventoryReleased"
        const val TOPIC: String = "inventory.events"

        @JvmStatic
        fun reserved(r: InventoryReservation, idempotent: Boolean): InventoryEvent =
            InventoryEvent(
                TYPE_RESERVED, r.id, r.productId, r.orderId,
                r.quantity, r.status, idempotent, Instant.now(),
            )

        @JvmStatic
        fun released(r: InventoryReservation, idempotent: Boolean): InventoryEvent =
            InventoryEvent(
                TYPE_RELEASED, r.id, r.productId, r.orderId,
                r.quantity, r.status, idempotent, Instant.now(),
            )
    }
}
