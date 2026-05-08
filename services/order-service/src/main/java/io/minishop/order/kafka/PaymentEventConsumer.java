package io.minishop.order.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.minishop.order.inbox.PaymentInboxRecord;
import io.minishop.order.inbox.PaymentInboxRepository;
import io.minishop.order.kafka.dto.InboundPaymentEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
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

        try {
            repository.save(PaymentInboxRecord.of(
                    event.paymentId(), event.orderId(), event.type(),
                    event.status(), event.externalRef(), payload
            ));
        } catch (DataIntegrityViolationException dup) {
            // 위의 existsByPaymentId() 와 save() 사이에는 (다른 인스턴스/스레드에 의한) 동시
            // INSERT 가 끼어들 수 있다. UNIQUE 제약이 안전망 역할을 하지만, 예외를 그냥 두면
            // 트랜잭션 롤백 → Kafka 가 같은 메시지를 무한 재전송 → 컨슈머가 멈춰버린다.
            // 여기서 흡수하면 "이미 누가 저장했음" 으로 정상 처리한 것과 같은 결과가 된다.
            meterRegistry.counter("inbox.consume", Tags.of("topic", "payment.events", "outcome", "duplicate")).increment();
            return;
        }
        meterRegistry.counter("inbox.consume", Tags.of("topic", "payment.events", "outcome", "stored")).increment();
        log.debug("Stored payment.events: type={} order={} payment={}", event.type(), event.orderId(), event.paymentId());
    }
}
