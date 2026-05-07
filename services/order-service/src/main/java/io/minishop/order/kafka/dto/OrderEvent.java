package io.minishop.order.kafka.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.minishop.order.domain.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrderEvent(
        String type,             // OrderCreated / OrderPaid / OrderFailed
        Long orderId,
        Long userId,
        OrderStatus status,
        BigDecimal totalAmount,
        String reason,           // OrderFailed에만 사용
        Instant occurredAt
) {
    public static final String TYPE_CREATED = "OrderCreated";
    public static final String TYPE_PAID = "OrderPaid";
    public static final String TYPE_FAILED = "OrderFailed";
    public static final String TOPIC = "order.events";
}
