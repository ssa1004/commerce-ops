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
 * 케이스 스터디(2026-05-07-payment-timeout-race)에서 발견한 부정합을 잡기 위한 잡.
 *
 * 시나리오: order는 timeout으로 보상되어 FAILED인데, payment-service는 SUCCESS로 commit + 이벤트 발행.
 * 이때 PaymentSucceeded 이벤트가 inbox에 들어와 있음에도 order는 FAILED. 이걸 *부정합*으로 잡아낸다.
 *
 * 동작:
 *  - 최근 N분 동안 발화된 PaymentSucceeded inbox 행에 대해
 *  - 같은 orderId의 Order 상태가 FAILED인 경우 → 부정합 카운트
 *  - 메트릭으로 노출 → 알람 가능 (Phase 2 Step 2 후속에서 알람 룰 추가 가능)
 *  - 자동 보정은 안 함. 사람 개입을 위한 신호.
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
        // gauge로 last_run_inconsistency_count도 의미 있지만 jvm 단순화 위해 counter 한 개만 사용.
    }

    @Scheduled(fixedDelayString = "${mini-shop.reconciliation.interval-ms:60000}")
    public void run() {
        Instant since = Instant.now().minus(props.lookbackMinutes(), ChronoUnit.MINUTES);
        long inconsistent = 0;
        long checked = 0;

        // 직접 SQL 페이징은 후속에서. 우선 페이지 단위 스캔으로 단순하게.
        var sample = paymentInboxRepository.findAll().stream()
                .filter(r -> "PaymentSucceeded".equals(r.getEventType()))
                .filter(r -> r.getReceivedAt().isAfter(since))
                .limit(props.batchSize())
                .toList();

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
