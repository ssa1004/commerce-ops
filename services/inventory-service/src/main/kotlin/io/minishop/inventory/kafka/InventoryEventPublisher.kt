package io.minishop.inventory.kafka

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import io.minishop.inventory.kafka.dto.InventoryEvent
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

@Component
class InventoryEventPublisher(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) {

    fun publish(event: InventoryEvent) {
        try {
            val json = objectMapper.writeValueAsString(event)
            kafkaTemplate.send(InventoryEvent.TOPIC, event.orderId.toString(), json)
                .whenComplete { _, ex ->
                    val outcome = if (ex == null) "sent" else "failed"
                    meterRegistry.counter(
                        "inventory.event.publish",
                        Tags.of("type", event.type, "outcome", outcome),
                    ).increment()
                    if (ex != null) {
                        log.warn(
                            "Failed to publish {} for order={} product={}: {}",
                            event.type, event.orderId, event.productId, ex.message,
                        )
                    }
                }
        } catch (e: JsonProcessingException) {
            log.error(
                "Failed to serialize InventoryEvent for order={} product={}",
                event.orderId, event.productId, e,
            )
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(InventoryEventPublisher::class.java)
    }
}
