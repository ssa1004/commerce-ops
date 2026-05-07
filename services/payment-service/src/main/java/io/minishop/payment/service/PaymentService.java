package io.minishop.payment.service;

import io.minishop.payment.domain.Payment;
import io.minishop.payment.exception.PaymentNotFoundException;
import io.minishop.payment.repository.PaymentRepository;
import io.minishop.payment.web.dto.CreatePaymentRequest;
import io.minishop.payment.web.dto.PgChargeRequest;
import io.minishop.payment.web.dto.PgChargeResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository paymentRepository;
    private final PgClient pgClient;

    public PaymentService(PaymentRepository paymentRepository, PgClient pgClient) {
        this.paymentRepository = paymentRepository;
        this.pgClient = pgClient;
    }

    @Transactional
    public Payment processPayment(CreatePaymentRequest request) {
        Payment payment = Payment.pending(request.orderId(), request.userId(), request.amount());
        payment = paymentRepository.save(payment);
        payment.recordAttempt();

        try {
            PgChargeResponse pgResponse = pgClient.charge(new PgChargeRequest(payment.getId(), payment.getAmount()));
            if (pgResponse.success()) {
                payment.markSuccess(pgResponse.reference());
            } else {
                payment.markFailed(pgResponse.reason());
            }
        } catch (PgClient.PgFailureException e) {
            log.warn("Payment {} failed via PG: {}", payment.getId(), e.getMessage());
            payment.markFailed(e.getMessage());
        }

        return payment;
    }

    @Transactional(readOnly = true)
    public Payment getById(Long id) {
        return paymentRepository.findById(id)
                .orElseThrow(() -> new PaymentNotFoundException(id));
    }
}
