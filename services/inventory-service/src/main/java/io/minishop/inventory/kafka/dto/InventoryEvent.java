package io.minishop.inventory.kafka.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.minishop.inventory.domain.InventoryReservation;
import io.minishop.inventory.domain.ReservationStatus;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record InventoryEvent(
        String type,             // InventoryReserved / InventoryReleased
        Long reservationId,
        Long productId,
        Long orderId,
        Integer quantity,
        ReservationStatus status,
        boolean idempotent,
        Instant occurredAt
) {
    public static final String TYPE_RESERVED = "InventoryReserved";
    public static final String TYPE_RELEASED = "InventoryReleased";
    public static final String TOPIC = "inventory.events";

    public static InventoryEvent reserved(InventoryReservation r, boolean idempotent) {
        return new InventoryEvent(TYPE_RESERVED, r.getId(), r.getProductId(), r.getOrderId(),
                r.getQuantity(), r.getStatus(), idempotent, Instant.now());
    }

    public static InventoryEvent released(InventoryReservation r, boolean idempotent) {
        return new InventoryEvent(TYPE_RELEASED, r.getId(), r.getProductId(), r.getOrderId(),
                r.getQuantity(), r.getStatus(), idempotent, Instant.now());
    }
}
