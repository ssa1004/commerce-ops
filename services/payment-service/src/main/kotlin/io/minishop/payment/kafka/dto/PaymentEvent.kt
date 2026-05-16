package io.minishop.payment.kafka.dto

import com.fasterxml.jackson.annotation.JsonInclude
import io.minishop.payment.domain.Payment
import io.minishop.payment.domain.PaymentStatus
import java.math.BigDecimal
import java.time.Instant

@JsonInclude(JsonInclude.Include.NON_NULL)
@JvmRecord
data class PaymentEvent(
    val type: String,             // PaymentSucceeded / PaymentFailed
    val paymentId: Long?,
    val orderId: Long?,
    val userId: Long?,
    val amount: BigDecimal?,
    val status: PaymentStatus?,
    val externalRef: String?,
    val failureReason: String?,
    val attempts: Int?,
    val occurredAt: Instant?,
) {
    companion object {
        const val TYPE_SUCCEEDED: String = "PaymentSucceeded"
        const val TYPE_FAILED: String = "PaymentFailed"
        const val TOPIC: String = "payment.events"

        @JvmStatic
        fun from(p: Payment): PaymentEvent {
            val type = if (p.status == PaymentStatus.SUCCESS) TYPE_SUCCEEDED else TYPE_FAILED
            return PaymentEvent(
                type = type,
                paymentId = p.id,
                orderId = p.orderId,
                userId = p.userId,
                amount = p.amount,
                status = p.status,
                externalRef = p.externalRef,
                failureReason = p.failureReason,
                attempts = p.attempts,
                occurredAt = Instant.now(),
            )
        }
    }
}
