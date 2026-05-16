package io.minishop.order.web.dto

import io.minishop.order.domain.Order
import io.minishop.order.domain.OrderStatus
import java.math.BigDecimal
import java.time.Instant

@JvmRecord
data class OrderResponse(
    val id: Long?,
    val userId: Long?,
    val status: OrderStatus?,
    val totalAmount: BigDecimal?,
    val items: List<OrderItemResponse>,
    val createdAt: Instant?,
    val updatedAt: Instant?,
) {
    companion object {
        @JvmStatic
        fun from(order: Order): OrderResponse = OrderResponse(
            order.id,
            order.userId,
            order.status,
            order.totalAmount,
            order.items.map { OrderItemResponse.from(it) },
            order.createdAt,
            order.updatedAt,
        )
    }
}
