package io.minishop.inventory.web.dto

import io.minishop.inventory.domain.InventoryReservation
import io.minishop.inventory.domain.ReservationStatus
import java.time.Instant

@JvmRecord
data class ReservationResponse(
    val id: Long?,
    val productId: Long?,
    val orderId: Long?,
    val quantity: Int?,
    val status: ReservationStatus?,
    val createdAt: Instant?,
    val releasedAt: Instant?,
    val idempotent: Boolean,
) {
    companion object {
        @JvmStatic
        fun from(r: InventoryReservation, idempotent: Boolean): ReservationResponse =
            ReservationResponse(
                r.id, r.productId, r.orderId, r.quantity,
                r.status, r.createdAt, r.releasedAt, idempotent,
            )
    }
}
