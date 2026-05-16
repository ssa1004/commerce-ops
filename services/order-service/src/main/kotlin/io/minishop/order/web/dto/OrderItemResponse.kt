package io.minishop.order.web.dto

import io.minishop.order.domain.OrderItem
import java.math.BigDecimal

@JvmRecord
data class OrderItemResponse(
    val id: Long?,
    val productId: Long?,
    val quantity: Int?,
    val price: BigDecimal?,
) {
    companion object {
        @JvmStatic
        fun from(item: OrderItem): OrderItemResponse =
            OrderItemResponse(item.id, item.productId, item.quantity, item.price)
    }
}
