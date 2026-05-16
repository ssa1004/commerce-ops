package io.minishop.payment.service

import io.minishop.payment.domain.Payment
import io.minishop.payment.exception.PaymentNotFoundException
import io.minishop.payment.kafka.PaymentEventPublisher
import io.minishop.payment.repository.PaymentRepository
import io.minishop.payment.web.dto.CreatePaymentRequest
import io.minishop.payment.web.dto.PgChargeRequest
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

@Service
open class PaymentService(
    private val paymentRepository: PaymentRepository,
    private val pgClient: PgClient,
    private val eventPublisher: PaymentEventPublisher,
) {

    @Transactional
    open fun processPayment(request: CreatePaymentRequest): Payment {
        var payment = Payment.pending(request.orderId, request.userId, request.amount)
        payment = paymentRepository.save(payment)
        payment.recordAttempt()

        try {
            val pgResponse = pgClient.charge(PgChargeRequest(payment.id, payment.amount))
            if (pgResponse.success) {
                payment.markSuccess(pgResponse.reference)
            } else {
                payment.markFailed(pgResponse.reason)
            }
        } catch (e: PgClient.PgFailureException) {
            log.warn("Payment {} failed via PG: {}", payment.id, e.message)
            payment.markFailed(e.message)
        }

        // 트랜잭션 커밋 직후에만 publish — DB 에 안 남은 결제가 이벤트로만 새어나가는 것을 방지.
        // 단, 커밋 후 publish 직전에 프로세스가 죽으면 "DB 는 SUCCESS 인데 이벤트는 못 갔다" 가 가능.
        // 이 위험을 줄이려면 Outbox 패턴으로 격상해야 하는데 (ADR-009 참고), Phase 3 까지의 절충안이다.
        // TODO(phase-3-step-3a): outbox 자리 (V2__create_outbox.sql + io.minishop.payment.outbox.*) 는
        //   준비되어 있음. 이 호출을 같은 트랜잭션 안에서 outboxRepository.save(OutboxEvent.pending(...)) 로
        //   바꾸고 order-service 와 동일 패턴의 폴러를 띄우면 격상 완료. (격상 계획은 outbox 패키지 javadoc 참고)
        val finalPayment = payment
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() {
                eventPublisher.publish(finalPayment)
            }
        })

        return payment
    }

    @Transactional(readOnly = true)
    open fun getById(id: Long): Payment =
        paymentRepository.findById(id).orElseThrow { PaymentNotFoundException(id) }

    companion object {
        private val log = LoggerFactory.getLogger(PaymentService::class.java)
    }
}
