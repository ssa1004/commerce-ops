package io.minishop.payment.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

public record PgChargeRequest(
        @NotNull Long paymentId,
        @NotNull @PositiveOrZero BigDecimal amount
) {}
