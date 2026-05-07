package io.minishop.payment.web.dto;

import io.minishop.payment.domain.Payment;
import io.minishop.payment.domain.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentResponse(
        Long id,
        Long orderId,
        Long userId,
        BigDecimal amount,
        PaymentStatus status,
        String externalRef,
        String failureReason,
        Integer attempts,
        Instant createdAt,
        Instant completedAt
) {
    public static PaymentResponse from(Payment p) {
        return new PaymentResponse(
                p.getId(), p.getOrderId(), p.getUserId(), p.getAmount(),
                p.getStatus(), p.getExternalRef(), p.getFailureReason(),
                p.getAttempts(), p.getCreatedAt(), p.getCompletedAt()
        );
    }
}
