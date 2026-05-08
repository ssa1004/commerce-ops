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
 * <p><b>잡는 시나리오</b>: order 는 호출 timeout 으로 보상되어 FAILED 가 됐는데, 같은 시각 payment-service
 * 는 SUCCESS 로 커밋 + PaymentSucceeded 이벤트를 발행. 그 이벤트가 inbox (다른 서비스 이벤트를 멱등
 * 저장하는 *거울 테이블*) 에 들어와도 order 는 여전히 FAILED 인 상태. 이렇게 *서로 다른 진실* 을 가진
 * 상태가 부정합이다.
 *
 * <p><b>동작</b>:
 * <ul>
 *   <li>최근 N분 안에 도착한 PaymentSucceeded inbox 행만 본다.</li>
 *   <li>같은 orderId 의 Order 가 FAILED 면 부정합 카운트 +1.</li>
 *   <li>메트릭 {@code reconciliation.inconsistency} 로 노출 — 알람 룰을 걸 수 있는 신호.</li>
 *   <li>자동 보정은 하지 않는다. 일시적 경합 (race) 인지 진짜 부정합인지 즉시 구별이 어렵고, 잘못된
 *       자동 보정은 새 사고로 이어진다. 사람 개입용 *신호* 로만 둔다.</li>
 * </ul>
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

        // 페이지 쿼리로 DB 단에서 eventType + receivedAt 으로 좁히고 batchSize 만큼만 가져온다.
        // inbox 가 수십만 행으로 자라도 메모리에 다 올리지 않는다 (이전엔 findAll().stream().filter(...) 였고,
        // inbox 크기가 작은 동안엔 문제가 없었지만 운영 적재 가정에선 OOM 위험이라 페이지 쿼리로 바꿨다).
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
