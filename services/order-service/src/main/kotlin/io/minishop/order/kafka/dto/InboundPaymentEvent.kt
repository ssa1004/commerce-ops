package io.minishop.order.kafka.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.math.BigDecimal
import java.time.Instant

/**
 * payment-service의 PaymentEvent를 그대로 받기 위한 양식.
 * 알 수 없는 필드는 무시 (다른 서비스가 필드를 추가해도 깨지지 않게).
 */
@JvmRecord
@JsonIgnoreProperties(ignoreUnknown = true)
data class InboundPaymentEvent(
    val type: String?,
    val paymentId: Long?,
    val orderId: Long?,
    val userId: Long?,
    val amount: BigDecimal?,
    val status: String?,
    val externalRef: String?,
    val failureReason: String?,
    val attempts: Int?,
    val occurredAt: Instant?,
)
