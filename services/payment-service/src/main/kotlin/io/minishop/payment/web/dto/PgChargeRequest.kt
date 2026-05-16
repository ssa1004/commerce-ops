package io.minishop.payment.web.dto

import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.PositiveOrZero
import java.math.BigDecimal

@JvmRecord
data class PgChargeRequest(
    @field:NotNull val paymentId: Long?,
    @field:NotNull @field:PositiveOrZero val amount: BigDecimal?,
)
