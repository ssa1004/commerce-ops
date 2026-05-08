package io.minishop.order.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.minishop.order.inbox.InventoryInboxRecord;
import io.minishop.order.inbox.InventoryInboxRepository;
import io.minishop.order.kafka.dto.InboundInventoryEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class InventoryEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(InventoryEventConsumer.class);

    private final InventoryInboxRepository repository;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public InventoryEventConsumer(InventoryInboxRepository repository, ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @KafkaListener(topics = "inventory.events", groupId = "order-service.inventory-inbox")
    @Transactional
    public void onMessage(String payload) {
        InboundInventoryEvent event;
        try {
            event = objectMapper.readValue(payload, InboundInventoryEvent.class);
        } catch (Exception e) {
            log.error("Cannot parse inventory event payload, skipping. raw={}", payload, e);
            meterRegistry.counter("inbox.consume", Tags.of("topic", "inventory.events", "outcome", "parse_error")).increment();
            return;
        }

        if (event.reservationId() == null) {
            log.warn("inventory event without reservationId, skipping: {}", event);
            meterRegistry.counter("inbox.consume", Tags.of("topic", "inventory.events", "outcome", "missing_key")).increment();
            return;
        }

        if (repository.existsByReservationId(event.reservationId())) {
            meterRegistry.counter("inbox.consume", Tags.of("topic", "inventory.events", "outcome", "duplicate")).increment();
            return;
        }

        try {
            repository.save(InventoryInboxRecord.of(
                    event.reservationId(), event.orderId(), event.productId(),
                    event.type(), event.status(), payload
            ));
        } catch (DataIntegrityViolationException dup) {
            // existsByReservationId() 와 save() 사이의 동시 INSERT race 를 흡수.
            // PaymentEventConsumer 의 같은 패턴 주석 참고.
            meterRegistry.counter("inbox.consume", Tags.of("topic", "inventory.events", "outcome", "duplicate")).increment();
            return;
        }
        meterRegistry.counter("inbox.consume", Tags.of("topic", "inventory.events", "outcome", "stored")).increment();
        log.debug("Stored inventory.events: type={} order={} reservation={}", event.type(), event.orderId(), event.reservationId());
    }
}
