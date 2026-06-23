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

    /**
     * 트랜잭션 *밖* 에서 fire-and-forget 발행 (order-service 의 outbox 경유와 다른 절충). 재고
     * 상태의 진실은 DB 의 reservation 행이고, 이 이벤트는 관측/하위 동기화용 부가 신호라 발행 실패가
     * 도메인 정합을 깨지 않는다 — 그래서 실패는 메트릭/로그만. (정확히-한-번 발행이 필요해지면
     * order-service 처럼 outbox 로 격상; DlqSource.OUTBOX 가 그 자리.)
     *
     * partition key = orderId — 같은 주문의 reserved/released 가 한 파티션에 순서대로 쌓이게.
     */
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
