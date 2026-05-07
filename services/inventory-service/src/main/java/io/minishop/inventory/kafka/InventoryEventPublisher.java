package io.minishop.inventory.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.minishop.inventory.kafka.dto.InventoryEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class InventoryEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(InventoryEventPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public InventoryEventPublisher(KafkaTemplate<String, String> kafkaTemplate,
                                   ObjectMapper objectMapper,
                                   MeterRegistry meterRegistry) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    public void publish(InventoryEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(InventoryEvent.TOPIC, String.valueOf(event.orderId()), json)
                    .whenComplete((res, ex) -> {
                        String outcome = ex == null ? "sent" : "failed";
                        meterRegistry.counter("inventory.event.publish",
                                Tags.of("type", event.type(), "outcome", outcome)).increment();
                        if (ex != null) {
                            log.warn("Failed to publish {} for order={} product={}: {}",
                                    event.type(), event.orderId(), event.productId(), ex.getMessage());
                        }
                    });
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize InventoryEvent for order={} product={}",
                    event.orderId(), event.productId(), e);
        }
    }
}
