package io.minishop.payment.kafka

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import io.minishop.payment.domain.Payment
import io.minishop.payment.kafka.dto.PaymentEvent
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

@Component
open class PaymentEventPublisher(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) {

    /**
     * 결제 처리 직후 fire-and-forget으로 발행한다.
     * 트랜잭션 밖이라 "DB는 SUCCESS인데 이벤트는 못 갔다"가 가능 — Phase 3에서 outbox로 보강 가능.
     * Phase 2에서는 단순함을 우선.
     */
    open fun publish(payment: Payment) {
        val event = PaymentEvent.from(payment)
        try {
            val json = objectMapper.writeValueAsString(event)
            kafkaTemplate.send(PaymentEvent.TOPIC, payment.orderId.toString(), json)
                .whenComplete { _, ex ->
                    val outcome = if (ex == null) "sent" else "failed"
                    meterRegistry.counter(
                        "payment.event.publish",
                        Tags.of("type", event.type, "outcome", outcome),
                    ).increment()
                    if (ex != null) {
                        log.warn(
                            "Failed to publish {} for order={}: {}",
                            event.type,
                            payment.orderId,
                            ex.message,
                        )
                    }
                }
        } catch (e: JsonProcessingException) {
            log.error("Failed to serialize PaymentEvent for order={}", payment.orderId, e)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(PaymentEventPublisher::class.java)
    }
}
