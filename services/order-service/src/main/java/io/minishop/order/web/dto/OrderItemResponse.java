package io.minishop.order.web.dto;

import io.minishop.order.domain.OrderItem;

import java.math.BigDecimal;

public record OrderItemResponse(
        Long id,
        Long productId,
        Integer quantity,
        BigDecimal price
) {
    public static OrderItemResponse from(OrderItem item) {
        return new OrderItemResponse(item.getId(), item.getProductId(), item.getQuantity(), item.getPrice());
    }
}
