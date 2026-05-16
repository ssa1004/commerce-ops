package io.minishop.payment.web.dto

import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.PositiveOrZero
import java.math.BigDecimal

@JvmRecord
data class CreatePaymentRequest(
    @field:NotNull val orderId: Long?,
    @field:NotNull val userId: Long?,
    @field:NotNull @field:PositiveOrZero val amount: BigDecimal?,
)
