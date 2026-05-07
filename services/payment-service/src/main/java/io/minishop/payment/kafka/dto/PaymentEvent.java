package io.minishop.payment.kafka.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.minishop.payment.domain.Payment;
import io.minishop.payment.domain.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PaymentEvent(
        String type,             // PaymentSucceeded / PaymentFailed
        Long paymentId,
        Long orderId,
        Long userId,
        BigDecimal amount,
        PaymentStatus status,
        String externalRef,
        String failureReason,
        Integer attempts,
        Instant occurredAt
) {
    public static final String TYPE_SUCCEEDED = "PaymentSucceeded";
    public static final String TYPE_FAILED = "PaymentFailed";
    public static final String TOPIC = "payment.events";

    public static PaymentEvent from(Payment p) {
        String type = p.getStatus() == PaymentStatus.SUCCESS ? TYPE_SUCCEEDED : TYPE_FAILED;
        return new PaymentEvent(
                type, p.getId(), p.getOrderId(), p.getUserId(), p.getAmount(),
                p.getStatus(), p.getExternalRef(), p.getFailureReason(),
                p.getAttempts(), Instant.now()
        );
    }
}
