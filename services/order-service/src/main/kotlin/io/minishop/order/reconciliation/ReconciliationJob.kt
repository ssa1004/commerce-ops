package io.minishop.order.reconciliation

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import io.minishop.order.domain.OrderStatus
import io.minishop.order.inbox.PaymentInboxRepository
import io.minishop.order.repository.OrderRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 케이스 스터디 (2026-05-07-payment-timeout-race) 에서 발견한 부정합을 잡기 위한 정기 점검 잡.
 *
 * **잡는 시나리오**: order 는 호출 timeout 으로 보상되어 FAILED 가 됐는데, 같은 시각 payment-service
 * 는 SUCCESS 로 커밋 + PaymentSucceeded 이벤트를 발행. 그 이벤트가 inbox (다른 서비스 이벤트를 멱등
 * 저장하는 *거울 테이블*) 에 들어와도 order 는 여전히 FAILED 인 상태. 이렇게 *서로 다른 진실* 을 가진
 * 상태가 부정합이다.
 *
 * **동작**:
 * - 최근 N분 안에 도착한 PaymentSucceeded inbox 행만 본다.
 * - 같은 orderId 의 Order 가 FAILED 면 부정합 카운트 +1.
 * - 메트릭 `reconciliation.inconsistency` 로 노출 — 알람 룰을 걸 수 있는 신호.
 * - 자동 보정은 하지 않는다. 일시적 경합 (race) 인지 진짜 부정합인지 즉시 구별이 어렵고, 잘못된
 *   자동 보정은 새 사고로 이어진다. 사람 개입용 *신호* 로만 둔다.
 */
@Component
@ConditionalOnProperty(
    prefix = "mini-shop.reconciliation",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class ReconciliationJob(
    private val orderRepository: OrderRepository,
    private val paymentInboxRepository: PaymentInboxRepository,
    private val props: ReconciliationProperties,
    private val meterRegistry: MeterRegistry,
) {

    @Scheduled(fixedDelayString = "\${mini-shop.reconciliation.interval-ms:60000}")
    fun run() {
        val since = Instant.now().minus(props.lookbackMinutes, ChronoUnit.MINUTES)
        var inconsistent = 0L
        var checked = 0L

        // 페이지 쿼리로 DB 단에서 eventType + receivedAt 으로 좁히고 batchSize 만큼만 가져온다.
        // inbox 가 수십만 행으로 자라도 메모리에 다 올리지 않는다 (이전엔 findAll().stream().filter(...) 였고,
        // inbox 크기가 작은 동안엔 문제가 없었지만 운영 적재 가정에선 OOM 위험이라 페이지 쿼리로 바꿨다).
        val sample = paymentInboxRepository.findByEventTypeAndReceivedAtAfterOrderByReceivedAtAsc(
            "PaymentSucceeded", since,
            PageRequest.of(0, props.batchSize),
        )

        for (record in sample) {
            checked++
            val order = orderRepository.findById(record.orderId!!)
            if (order.isPresent && order.get().status == OrderStatus.FAILED) {
                inconsistent++
                log.warn(
                    "Reconciliation inconsistency: order={} status=FAILED but payment {} SUCCESS (externalRef={})",
                    record.orderId, record.paymentId, record.externalRef,
                )
                meterRegistry.counter(
                    "reconciliation.inconsistency",
                    Tags.of("kind", "order_failed_payment_succeeded"),
                ).increment()
            }
        }

        meterRegistry.counter("reconciliation.run", Tags.of("outcome", "ok")).increment()
        if (checked > 0) {
            log.info("Reconciliation scanned={} inconsistent={}", checked, inconsistent)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(ReconciliationJob::class.java)
    }
}
