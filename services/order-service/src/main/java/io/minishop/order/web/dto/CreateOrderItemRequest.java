package io.minishop.order.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

public record CreateOrderItemRequest(
        @NotNull Long productId,
        @NotNull @Min(1) Integer quantity,
        @NotNull @PositiveOrZero BigDecimal price
) {}
