package io.minishop.payment.service;

import io.minishop.payment.domain.Payment;
import io.minishop.payment.exception.PaymentNotFoundException;
import io.minishop.payment.kafka.PaymentEventPublisher;
import io.minishop.payment.repository.PaymentRepository;
import io.minishop.payment.web.dto.CreatePaymentRequest;
import io.minishop.payment.web.dto.PgChargeRequest;
import io.minishop.payment.web.dto.PgChargeResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository paymentRepository;
    private final PgClient pgClient;
    private final PaymentEventPublisher eventPublisher;

    public PaymentService(PaymentRepository paymentRepository, PgClient pgClient, PaymentEventPublisher eventPublisher) {
        this.paymentRepository = paymentRepository;
        this.pgClient = pgClient;
        this.eventPublisher = eventPublisher;
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

        // 트랜잭션 commit 직후에만 publish — DB에 없는 이벤트가 발행되는 것을 방지.
        // (Phase 3 outbox로 강화 시점까지의 단순한 절충안.)
        Payment finalPayment = payment;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                eventPublisher.publish(finalPayment);
            }
        });

        return payment;
    }

    @Transactional(readOnly = true)
    public Payment getById(Long id) {
        return paymentRepository.findById(id)
                .orElseThrow(() -> new PaymentNotFoundException(id));
    }
}
