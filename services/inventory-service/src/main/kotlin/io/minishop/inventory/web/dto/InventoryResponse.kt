package io.minishop.inventory.web.dto

import io.minishop.inventory.domain.Inventory

@JvmRecord
data class InventoryResponse(
    val productId: Long?,
    val availableQuantity: Int?,
) {
    companion object {
        @JvmStatic
        fun from(inv: Inventory): InventoryResponse =
            InventoryResponse(inv.productId, inv.availableQuantity)
    }
}
