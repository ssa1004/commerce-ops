package io.minishop.order.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record CreateOrderRequest(
        @NotNull Long userId,
        @NotEmpty @Valid List<CreateOrderItemRequest> items
) {}
