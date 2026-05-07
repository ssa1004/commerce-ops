package io.minishop.order.kafka.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record InboundInventoryEvent(
        String type,
        Long reservationId,
        Long productId,
        Long orderId,
        Integer quantity,
        String status,
        Boolean idempotent,
        Instant occurredAt
) {}
