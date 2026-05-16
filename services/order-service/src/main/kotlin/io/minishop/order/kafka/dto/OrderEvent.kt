package io.minishop.order.kafka.dto

import com.fasterxml.jackson.annotation.JsonInclude
import io.minishop.order.domain.OrderStatus
import java.math.BigDecimal
import java.time.Instant

@JvmRecord
@JsonInclude(JsonInclude.Include.NON_NULL)
data class OrderEvent(
    val type: String,             // OrderCreated / OrderPaid / OrderFailed
    val orderId: Long?,
    val userId: Long?,
    val status: OrderStatus?,
    val totalAmount: BigDecimal?,
    val reason: String?,           // OrderFailed에만 사용
    val occurredAt: Instant?,
) {
    companion object {
        const val TYPE_CREATED: String = "OrderCreated"
        const val TYPE_PAID: String = "OrderPaid"
        const val TYPE_FAILED: String = "OrderFailed"
        const val TOPIC: String = "order.events"
    }
}
