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

        // 트랜잭션 커밋 직후에만 publish — DB 에 안 남은 결제가 이벤트로만 새어나가는 것을 방지.
        // 단, 커밋 후 publish 직전에 프로세스가 죽으면 "DB 는 SUCCESS 인데 이벤트는 못 갔다" 가 가능.
        // 이 위험을 줄이려면 Outbox 패턴으로 격상해야 하는데 (ADR-009 참고), Phase 3 까지의 절충안이다.
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
