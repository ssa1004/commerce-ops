package io.minishop.order.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * outbox 테이블에 쌓인 이벤트를 Kafka 로 발행하는 폴러.
 *
 * <p><b>다중 인스턴스 안전</b> — {@code SELECT ... FOR UPDATE SKIP LOCKED} (PostgreSQL: 다른 트랜잭션이
 * 잡고 있는 행은 건너뛰고 가져오는 옵션) 로 같은 행을 두 인스턴스가 동시에 잡지 않게 한다.
 *
 * <p><b>전달 보장은 at-least-once</b> — 메시지는 최소 한 번 도달, 가끔 중복. Kafka 로 send 한 직후 +
 * markSent flush 직전 사이에 죽으면 재시작 후 같은 이벤트가 한 번 더 발행된다. consumer 측 멱등성
 * (UNIQUE 제약 등) 으로 중복을 흡수한다.
 *
 * <p><b>행마다 트랜잭션을 분리한 이유</b> — 한 트랜잭션에 N 행을 묶으면 N 번째 행의 markSent flush
 * 실패가 1..N-1 행까지 같이 롤백시킨다. 그러나 1..N-1 의 Kafka 메시지는 이미 나간 상태라 되돌릴 수 없다.
 * 결과: DB 는 PENDING 으로 돌아왔는데 메시지는 이미 갔다 → 다음 폴에서 *재발행*. 행마다 트랜잭션을 끊으면 그 영향이 한 행에 갇힌다.
 *
 * <p><b>Kafka send 를 트랜잭션 *안* 에서 호출하는 안티패턴</b> — 일반론으론 외부 IO 를 트랜잭션 안에 두지 않는 게 원칙이지만 (DB 락
 * 점유 시간이 늘어남), 이 폴러는 외부 부하가 아닌 *자기 행* 의 lock 만 잡고 있고, 그 락을 잡고
 * 발행해야 다른 폴러 인스턴스의 중복 발행을 막을 수 있다. 단점은 Kafka 가 느리면 락이 길게 잡히는 것 →
 * {@code .get(sendTimeoutMs)} 로 상한을 걸어 행 락이 무기한 잡히지 않게 한다.
 *
 * <p><b>MDC outboxRunId</b> — 한 번의 {@code poll()} 호출에서 처리된 모든 행 로그를 같은 키로 묶기 위한
 * 백그라운드용 식별자. 요청 thread 의 traceId 와는 별개. {@code correlation-mdc-starter} 가 정식 도입되기
 * 전까지의 임시 처방.
 */
@Component
@ConditionalOnProperty(prefix = "mini-shop.outbox.poller", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);
    private static final String MDC_RUN_ID = "outboxRunId";

    private final OutboxRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TransactionTemplate tx;
    private final OutboxProperties props;
    private final MeterRegistry meterRegistry;

    public OutboxPoller(OutboxRepository repository,
                        KafkaTemplate<String, String> kafkaTemplate,
                        TransactionTemplate transactionTemplate,
                        OutboxProperties props,
                        MeterRegistry meterRegistry) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.tx = transactionTemplate;
        this.props = props;
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(fixedDelayString = "${mini-shop.outbox.poller.interval-ms:1000}")
    public void poll() {
        String runId = UUID.randomUUID().toString().substring(0, 8);
        MDC.put(MDC_RUN_ID, runId);
        try {
            int batchSize = props.poller().batchSize();
            int processed = 0;
            for (int i = 0; i < batchSize; i++) {
                Boolean handled = tx.execute(s -> processOne());
                if (handled == null || !handled) break;   // 더 이상 PENDING 없음 → 다음 주기로
                processed++;
                if (Thread.currentThread().isInterrupted()) break;
            }
            if (processed > 0) {
                log.debug("Outbox poller processed {} events in run {}", processed, runId);
            }
        } finally {
            MDC.remove(MDC_RUN_ID);
        }
    }

    /**
     * 한 행을 하나의 트랜잭션 안에서: 잠그고, Kafka 로 보내고, 상태를 갱신한다.
     * @return 처리한 행이 있으면 true, PENDING 이 비어있으면 false.
     */
    private boolean processOne() {
        Optional<OutboxEvent> picked = repository.findNextPendingForUpdate();
        if (picked.isEmpty()) return false;
        OutboxEvent event = picked.get();

        long sendTimeoutMs = props.poller().sendTimeoutMs();
        try {
            // KafkaTemplate.send().get(timeout) — broker 지연으로 send 가 길어져도 행 락을
            // 무기한 잡지 않게 상한을 건다. timeout 없이 .get() 만 부르면 Kafka 가 느릴 때
            // 같은 행을 다른 poller 인스턴스도 SKIP LOCKED 로 건너뛰어 처리 정체가 cascade 한다.
            kafkaTemplate.send(event.getTopic(), String.valueOf(event.getAggregateId()), event.getPayload())
                    .get(sendTimeoutMs, TimeUnit.MILLISECONDS);
            event.markSent();
            meterRegistry.counter("outbox.publish", Tags.of("topic", event.getTopic(), "outcome", "sent")).increment();
        } catch (InterruptedException ie) {
            // 인터럽트도 다른 실패와 같은 attempt 회계 (handleFailure) 를 거쳐야 한다.
            // 직접 markAttemptFailed 만 호출하면 인터럽트가 반복되는 행 (예: 프로세스 종료가
            // 진행 중인 상태에서 매 폴마다 인터럽트가 도달) 이 maxAttempts 에 도달해도
            // 영구히 PENDING 으로 남는다 → 재시작 후에도 같은 행이 다시 시도되어 정체.
            Thread.currentThread().interrupt();
            handleFailure(event, "interrupted: " + ie.getMessage());
            meterRegistry.counter("outbox.publish", Tags.of("topic", event.getTopic(), "outcome", "interrupted")).increment();
        } catch (TimeoutException te) {
            log.warn("Kafka send timed out after {}ms for outbox id={} topic={}",
                    sendTimeoutMs, event.getId(), event.getTopic());
            handleFailure(event, "send timeout after " + sendTimeoutMs + "ms");
            String outcome = event.getStatus() == OutboxStatus.FAILED ? "failed" : "timeout";
            meterRegistry.counter("outbox.publish", Tags.of("topic", event.getTopic(), "outcome", outcome)).increment();
        } catch (ExecutionException e) {
            String reason = e.getCause() == null ? e.getMessage() : e.getCause().getMessage();
            log.warn("Kafka send failed for outbox id={} topic={}: {}", event.getId(), event.getTopic(), reason);
            handleFailure(event, reason);
            String outcome = event.getStatus() == OutboxStatus.FAILED ? "failed" : "retry";
            meterRegistry.counter("outbox.publish", Tags.of("topic", event.getTopic(), "outcome", outcome)).increment();
        }
        return true;
    }

    /**
     * 실패 처리 — attempts 가 maxAttempts 도달 직전이면 markFailed 로 종결, 아니면 markAttemptFailed.
     *
     * <p>{@code markFailed} / {@code markAttemptFailed} 는 둘 다 attempts 를 +1 하므로 *둘 중 하나만*
     * 호출해야 한다. 호출 후 {@code event.getStatus()} 로 결과를 분기하면 호출자가 메트릭 outcome
     * (retry vs failed) 을 일관되게 정할 수 있다.
     */
    private void handleFailure(OutboxEvent event, String reason) {
        if (event.getAttempts() + 1 >= props.poller().maxAttempts()) {
            event.markFailed(reason);
        } else {
            event.markAttemptFailed(reason);
        }
    }
}
