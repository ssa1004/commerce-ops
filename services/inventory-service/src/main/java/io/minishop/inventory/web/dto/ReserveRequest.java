package io.minishop.inventory.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ReserveRequest(
        @NotNull Long productId,
        @NotNull Long orderId,
        @NotNull @Min(1) Integer quantity
) {}
