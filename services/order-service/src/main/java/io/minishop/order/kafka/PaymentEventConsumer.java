package io.minishop.order.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.minishop.order.inbox.PaymentInboxRecord;
import io.minishop.order.inbox.PaymentInboxRepository;
import io.minishop.order.kafka.dto.InboundPaymentEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class PaymentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumer.class);

    private final PaymentInboxRepository repository;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public PaymentEventConsumer(PaymentInboxRepository repository, ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @KafkaListener(topics = "payment.events", groupId = "order-service.payment-inbox")
    @Transactional
    public void onMessage(String payload) {
        InboundPaymentEvent event;
        try {
            event = objectMapper.readValue(payload, InboundPaymentEvent.class);
        } catch (Exception e) {
            log.error("Cannot parse payment event payload, skipping. raw={}", payload, e);
            meterRegistry.counter("inbox.consume", Tags.of("topic", "payment.events", "outcome", "parse_error")).increment();
            return;
        }

        if (event.paymentId() == null) {
            log.warn("payment event without paymentId, skipping: {}", event);
            meterRegistry.counter("inbox.consume", Tags.of("topic", "payment.events", "outcome", "missing_key")).increment();
            return;
        }

        // 멱등성: UNIQUE(payment_id) 제약 덕분에 같은 이벤트가 두 번 와도 한 행만 남음.
        // (Kafka 는 at-least-once 라 같은 메시지가 가끔 중복 도달함 — 그걸 여기서 흡수)
        if (repository.existsByPaymentId(event.paymentId())) {
            meterRegistry.counter("inbox.consume", Tags.of("topic", "payment.events", "outcome", "duplicate")).increment();
            return;
        }

        repository.save(PaymentInboxRecord.of(
                event.paymentId(), event.orderId(), event.type(),
                event.status(), event.externalRef(), payload
        ));
        meterRegistry.counter("inbox.consume", Tags.of("topic", "payment.events", "outcome", "stored")).increment();
        log.debug("Stored payment.events: type={} order={} payment={}", event.type(), event.orderId(), event.paymentId());
    }
}
