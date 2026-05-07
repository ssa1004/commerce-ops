package io.minishop.inventory.web.dto;

import io.minishop.inventory.domain.Inventory;

public record InventoryResponse(
        Long productId,
        Integer availableQuantity
) {
    public static InventoryResponse from(Inventory inv) {
        return new InventoryResponse(inv.getProductId(), inv.getAvailableQuantity());
    }
}
