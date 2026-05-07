package io.minishop.order.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

@Component
public class PaymentClient {

    private static final Logger log = LoggerFactory.getLogger(PaymentClient.class);

    private final RestClient client;

    public PaymentClient(@Qualifier("paymentRestClient") RestClient client) {
        this.client = client;
    }

    public PaymentResult charge(Long orderId, Long userId, BigDecimal amount) {
        try {
            return client.post()
                    .uri("/payments")
                    .body(new PaymentRequest(orderId, userId, amount))
                    .retrieve()
                    .onStatus(
                            (HttpStatusCode s) -> s.value() != HttpStatus.CREATED.value()
                                    && s.value() != HttpStatus.PAYMENT_REQUIRED.value(),
                            (req, res) -> {
                                throw new PaymentInfraException("payment-service returned " + res.getStatusCode());
                            }
                    )
                    .body(PaymentResult.class);
        } catch (ResourceAccessException e) {
            log.warn("payment-service unreachable: {}", e.getMessage());
            throw new PaymentInfraException("payment-service unreachable: " + e.getMessage(), e);
        }
    }

    public record PaymentRequest(Long orderId, Long userId, BigDecimal amount) {}

    public record PaymentResult(
            Long id,
            Long orderId,
            String status,        // SUCCESS / FAILED
            String externalRef,
            String failureReason
    ) {
        public boolean isSuccess() { return "SUCCESS".equals(status); }
    }

    public static class PaymentInfraException extends RuntimeException {
        public PaymentInfraException(String message) { super(message); }
        public PaymentInfraException(String message, Throwable cause) { super(message, cause); }
    }
}
