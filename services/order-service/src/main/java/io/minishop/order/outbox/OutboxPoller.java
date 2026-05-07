package io.minishop.order.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * outbox에 쌓인 이벤트를 Kafka로 publish.
 * - 동시 인스턴스 안전: SELECT ... FOR UPDATE SKIP LOCKED (PostgreSQL).
 * - At-least-once: 발행 후 mark SENT 사이에 장애 → 재시도 시 동일 이벤트가 한 번 더 갈 수 있음. consumer 측 멱등성으로 흡수.
 * - 트레이스 연속성은 Phase 3 후속에서 보강 (현재는 polling 트레이스가 새로 시작).
 */
@Component
@ConditionalOnProperty(prefix = "mini-shop.outbox.poller", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

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
        tx.executeWithoutResult(s -> {
            List<OutboxEvent> batch = repository.findPendingForUpdate(props.poller().batchSize());
            if (batch.isEmpty()) return;

            log.debug("Outbox poller picked {} pending events", batch.size());
            for (OutboxEvent event : batch) {
                try {
                    kafkaTemplate.send(event.getTopic(), String.valueOf(event.getAggregateId()), event.getPayload())
                            .get();
                    event.markSent();
                    meterRegistry.counter("outbox.publish", Tags.of("topic", event.getTopic(), "outcome", "sent")).increment();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    event.markAttemptFailed(ie.getMessage());
                    meterRegistry.counter("outbox.publish", Tags.of("topic", event.getTopic(), "outcome", "interrupted")).increment();
                    return;
                } catch (ExecutionException e) {
                    String reason = e.getCause() == null ? e.getMessage() : e.getCause().getMessage();
                    log.warn("Kafka send failed for outbox id={} topic={}: {}", event.getId(), event.getTopic(), reason);
                    if (event.getAttempts() + 1 >= props.poller().maxAttempts()) {
                        event.markFailed(reason);
                        meterRegistry.counter("outbox.publish", Tags.of("topic", event.getTopic(), "outcome", "failed")).increment();
                    } else {
                        event.markAttemptFailed(reason);
                        meterRegistry.counter("outbox.publish", Tags.of("topic", event.getTopic(), "outcome", "retry")).increment();
                    }
                }
            }
        });
    }
}
