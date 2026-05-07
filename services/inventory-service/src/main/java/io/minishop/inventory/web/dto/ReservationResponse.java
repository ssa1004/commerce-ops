package io.minishop.inventory.web.dto;

import io.minishop.inventory.domain.InventoryReservation;
import io.minishop.inventory.domain.ReservationStatus;

import java.time.Instant;

public record ReservationResponse(
        Long id,
        Long productId,
        Long orderId,
        Integer quantity,
        ReservationStatus status,
        Instant createdAt,
        Instant releasedAt,
        boolean idempotent
) {
    public static ReservationResponse from(InventoryReservation r, boolean idempotent) {
        return new ReservationResponse(
                r.getId(), r.getProductId(), r.getOrderId(), r.getQuantity(),
                r.getStatus(), r.getCreatedAt(), r.getReleasedAt(), idempotent
        );
    }
}
