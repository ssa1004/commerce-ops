package io.minishop.order.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import io.minishop.order.inbox.InventoryInboxRecord
import io.minishop.order.inbox.InventoryInboxRepository
import io.minishop.order.kafka.dto.InboundInventoryEvent
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

@Component
class InventoryEventConsumer(
    private val repository: InventoryInboxRepository,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
    private val transactionTemplate: TransactionTemplate,
) {

    /**
     * inventory.events consume → inbox 저장. ack-mode=MANUAL_IMMEDIATE 라 *명시 ack* 가 필요.
     *
     * 처리 순서:
     * 1. parse — 실패 시 즉시 ack (DLT 로 보낼 가치 없음, 그냥 흡수). poison pill 무한 루프 방지.
     * 2. [TransactionTemplate.execute] 안에서 inbox 저장. 정상 return 하면 트랜잭션 커밋 완료
     *    — 여기서 throw 하면 spring-kafka 의 DefaultErrorHandler 가 retry / DLT 처리 (ack 안 함).
     * 3. 트랜잭션이 무사히 커밋된 *후* `acknowledgment.acknowledge()` — 트랜잭션 ↔ ack 의
     *    순서 보장. 트랜잭션이 롤백되면 ack 안 일어나 같은 메시지가 다시 오고, inbox 의 UNIQUE
     *    제약이 중복을 흡수 (멱등성).
     *
     * 이전 패턴 (메서드에 `@Transactional` + ack-mode=record) 도 spring-kafka 의 timing
     * 으로 *대부분* 안전하지만, 트랜잭션 커밋과 자동 ack 의 순서가 *명시* 되지 않아 사고 회고 시
     * 의심 포인트가 됨. ADR-021 참조.
     */
    @KafkaListener(topics = ["inventory.events"], groupId = "order-service.inventory-inbox")
    fun onMessage(payload: String, acknowledgment: Acknowledgment) {
        val event: InboundInventoryEvent = try {
            objectMapper.readValue(payload, InboundInventoryEvent::class.java)
        } catch (e: Exception) {
            log.error("Cannot parse inventory event payload, skipping. raw={}", payload, e)
            meterRegistry.counter(
                "inbox.consume",
                Tags.of("topic", "inventory.events", "outcome", "parse_error"),
            ).increment()
            acknowledgment.acknowledge()
            return
        }

        if (event.reservationId == null) {
            log.warn("inventory event without reservationId, skipping: {}", event)
            meterRegistry.counter(
                "inbox.consume",
                Tags.of("topic", "inventory.events", "outcome", "missing_key"),
            ).increment()
            acknowledgment.acknowledge()
            return
        }

        transactionTemplate.executeWithoutResult { persistInbox(event, payload) }
        // executeWithoutResult 가 정상 return = 트랜잭션 커밋 완료. 여기서 ack.
        acknowledgment.acknowledge()
    }

    private fun persistInbox(event: InboundInventoryEvent, payload: String) {
        if (repository.existsByReservationId(event.reservationId!!)) {
            meterRegistry.counter(
                "inbox.consume",
                Tags.of("topic", "inventory.events", "outcome", "duplicate"),
            ).increment()
            return
        }

        try {
            repository.save(
                InventoryInboxRecord.of(
                    event.reservationId,
                    event.orderId!!,
                    event.productId!!,
                    event.type ?: "",
                    event.status ?: "",
                    payload,
                ),
            )
        } catch (_: DataIntegrityViolationException) {
            // existsByReservationId() 와 save() 사이의 동시 INSERT race 를 흡수.
            // PaymentEventConsumer 의 같은 패턴 주석 참고.
            meterRegistry.counter(
                "inbox.consume",
                Tags.of("topic", "inventory.events", "outcome", "duplicate"),
            ).increment()
            return
        }
        meterRegistry.counter(
            "inbox.consume",
            Tags.of("topic", "inventory.events", "outcome", "stored"),
        ).increment()
        log.debug(
            "Stored inventory.events: type={} order={} reservation={}",
            event.type, event.orderId, event.reservationId,
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(InventoryEventConsumer::class.java)
    }
}
