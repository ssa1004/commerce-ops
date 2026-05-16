package io.minishop.payment.web.dto

import io.minishop.payment.domain.Payment
import io.minishop.payment.domain.PaymentStatus
import java.math.BigDecimal
import java.time.Instant

@JvmRecord
data class PaymentResponse(
    val id: Long?,
    val orderId: Long?,
    val userId: Long?,
    val amount: BigDecimal?,
    val status: PaymentStatus?,
    val externalRef: String?,
    val failureReason: String?,
    val attempts: Int?,
    val createdAt: Instant?,
    val completedAt: Instant?,
) {
    companion object {
        @JvmStatic
        fun from(p: Payment): PaymentResponse = PaymentResponse(
            id = p.id,
            orderId = p.orderId,
            userId = p.userId,
            amount = p.amount,
            status = p.status,
            externalRef = p.externalRef,
            failureReason = p.failureReason,
            attempts = p.attempts,
            createdAt = p.createdAt,
            completedAt = p.completedAt,
        )
    }
}
