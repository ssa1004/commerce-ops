package io.minishop.inventory.web.dto

import jakarta.validation.constraints.NotNull

@JvmRecord
data class ReleaseRequest(
    @field:NotNull val productId: Long?,
    @field:NotNull val orderId: Long?,
)
