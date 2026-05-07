package io.minishop.inventory.web.dto;

import jakarta.validation.constraints.NotNull;

public record ReleaseRequest(
        @NotNull Long productId,
        @NotNull Long orderId
) {}
