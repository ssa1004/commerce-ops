package io.minishop.order.kafka.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.time.Instant

@JvmRecord
@JsonIgnoreProperties(ignoreUnknown = true)
data class InboundInventoryEvent(
    val type: String?,
    val reservationId: Long?,
    val productId: Long?,
    val orderId: Long?,
    val quantity: Int?,
    val status: String?,
    val idempotent: Boolean?,
    val occurredAt: Instant?,
)
