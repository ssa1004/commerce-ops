package io.minishop.payment.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.minishop.payment.domain.Payment;
import io.minishop.payment.kafka.dto.PaymentEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class PaymentEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public PaymentEventPublisher(KafkaTemplate<String, String> kafkaTemplate,
                                 ObjectMapper objectMapper,
                                 MeterRegistry meterRegistry) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 결제 처리 직후 fire-and-forget으로 발행한다.
     * 트랜잭션 밖이라 "DB는 SUCCESS인데 이벤트는 못 갔다"가 가능 — Phase 3에서 outbox로 보강 가능.
     * Phase 2에서는 단순함을 우선.
     */
    public void publish(Payment payment) {
        PaymentEvent event = PaymentEvent.from(payment);
        try {
            String json = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(PaymentEvent.TOPIC, String.valueOf(payment.getOrderId()), json)
                    .whenComplete((res, ex) -> {
                        String outcome = ex == null ? "sent" : "failed";
                        meterRegistry.counter("payment.event.publish",
                                Tags.of("type", event.type(), "outcome", outcome)).increment();
                        if (ex != null) {
                            log.warn("Failed to publish {} for order={}: {}", event.type(), payment.getOrderId(), ex.getMessage());
                        }
                    });
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize PaymentEvent for order={}", payment.getOrderId(), e);
        }
    }
}
