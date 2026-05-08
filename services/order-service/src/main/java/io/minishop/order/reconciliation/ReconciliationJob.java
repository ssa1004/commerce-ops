package io.minishop.order.reconciliation;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.minishop.order.domain.OrderStatus;
import io.minishop.order.inbox.PaymentInboxRecord;
import io.minishop.order.inbox.PaymentInboxRepository;
import io.minishop.order.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 케이스 스터디 (2026-05-07-payment-timeout-race) 에서 발견한 부정합을 잡기 위한 정기 점검 잡.
 *
 * 시나리오: order 는 timeout 으로 보상되어 FAILED 인데, payment-service 는 SUCCESS 로 커밋 + 이벤트 발행.
 * 이때 PaymentSucceeded 이벤트가 inbox (다른 서비스 이벤트를 멱등 저장하는 거울 테이블) 에 들어와
 * 있음에도 order 는 FAILED. 이걸 *부정합* (서로 다른 진실을 가진 상태) 으로 잡아낸다.
 *
 * 동작:
 *  - 최근 N분 동안 도착한 PaymentSucceeded inbox 행에 대해
 *  - 같은 orderId 의 Order 상태가 FAILED 인 경우 → 부정합 카운트 증가
 *  - 메트릭으로 노출 → 알람 가능 (Phase 2 Step 2 후속에서 알람 룰 추가 가능)
 *  - 자동 보정은 하지 않음. 사람 개입을 위한 신호로만 둠 (자동 보정은 race condition 때문에 위험).
 */
@Component
@ConditionalOnProperty(prefix = "mini-shop.reconciliation", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);

    private final OrderRepository orderRepository;
    private final PaymentInboxRepository paymentInboxRepository;
    private final ReconciliationProperties props;
    private final MeterRegistry meterRegistry;

    public ReconciliationJob(OrderRepository orderRepository,
                             PaymentInboxRepository paymentInboxRepository,
                             ReconciliationProperties props,
                             MeterRegistry meterRegistry) {
        this.orderRepository = orderRepository;
        this.paymentInboxRepository = paymentInboxRepository;
        this.props = props;
        this.meterRegistry = meterRegistry;
        // gauge (현재 값을 그대로 노출하는 메트릭) 로 last_run_inconsistency_count 도 의미 있지만,
        // 단순화를 위해 counter (누적 증가 메트릭) 한 개만 사용.
    }

    @Scheduled(fixedDelayString = "${mini-shop.reconciliation.interval-ms:60000}")
    public void run() {
        Instant since = Instant.now().minus(props.lookbackMinutes(), ChronoUnit.MINUTES);
        long inconsistent = 0;
        long checked = 0;

        // DB 단에서 eventType + receivedAt 으로 좁히고 batchSize 만큼만 가져온다 — inbox 가 수십만 행이 돼도
        // 메모리에 다 올리지 않는다 (예전엔 findAll().stream().filter(...) 였음).
        var sample = paymentInboxRepository.findByEventTypeAndReceivedAtAfterOrderByReceivedAtAsc(
                "PaymentSucceeded", since,
                org.springframework.data.domain.PageRequest.of(0, props.batchSize()));

        for (PaymentInboxRecord record : sample) {
            checked++;
            var order = orderRepository.findById(record.getOrderId());
            if (order.isPresent() && order.get().getStatus() == OrderStatus.FAILED) {
                inconsistent++;
                log.warn("Reconciliation inconsistency: order={} status=FAILED but payment {} SUCCESS (externalRef={})",
                        record.getOrderId(), record.getPaymentId(), record.getExternalRef());
                meterRegistry.counter("reconciliation.inconsistency",
                        Tags.of("kind", "order_failed_payment_succeeded")).increment();
            }
        }

        meterRegistry.counter("reconciliation.run", Tags.of("outcome", "ok")).increment();
        if (checked > 0) {
            log.info("Reconciliation scanned={} inconsistent={}", checked, inconsistent);
        }
    }
}
