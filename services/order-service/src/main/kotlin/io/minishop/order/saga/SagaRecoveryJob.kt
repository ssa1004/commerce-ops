package io.minishop.order.saga

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import io.minishop.order.domain.OrderStatus
import io.minishop.order.kafka.dto.OrderEvent
import io.minishop.order.outbox.OutboxService
import io.minishop.order.repository.OrderRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

/**
 * 멈춘(stuck) SAGA 복구 잡 — 크래시 안전망.
 *
 * 정상 흐름은 동기라 한 요청 안에서 PAID 또는 FAILED 로 끝난다. 그런데 프로세스가 reserve~
 * 결제 사이에서 죽으면 주문은 PENDING 에 멈추고 잡아둔 재고는 고아가 된다. 이 잡은 일정 시간
 * 넘게 PENDING 인 주문을 찾아 [CompensationRunner] 로 영속 saga_step 을 역순 보상(release)하고
 * FAILED 로 종결 + OrderFailed outbox 발행한다.
 *
 * STARTED 의도 로그 덕에, reserve 성공 후 step 기록 전에 죽은 경우라도 release(멱등)로 안전하게
 * 정리된다.
 */
@Component
@ConditionalOnProperty(
    prefix = "mini-shop.saga-recovery",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class SagaRecoveryJob(
    private val orderRepository: OrderRepository,
    private val compensationRunner: CompensationRunner,
    private val outboxService: OutboxService,
    private val props: SagaRecoveryProperties,
    private val meterRegistry: MeterRegistry,
    private val tx: TransactionTemplate,
) {

    @Scheduled(fixedDelayString = "\${mini-shop.saga-recovery.interval-ms:60000}")
    fun run() {
        val threshold = Instant.now().minusSeconds(props.stuckAfterSeconds)
        val stuck = orderRepository.findByStatusAndCreatedAtBefore(
            OrderStatus.PENDING, threshold, PageRequest.of(0, props.batchSize),
        )
        if (stuck.isEmpty) {
            return
        }

        for (order in stuck) {
            val orderId = order.id!!
            val summary = compensationRunner.compensate(orderId)
            // 그새 다른 경로에서 종결됐을 수 있으니 PENDING 일 때만 FAILED 로 마감.
            tx.executeWithoutResult { _ ->
                orderRepository.findById(orderId).ifPresent { o ->
                    if (o.status == OrderStatus.PENDING) {
                        o.markFailed()
                        outboxService.enqueue(
                            "Order", orderId, OrderEvent.TYPE_FAILED, OrderEvent.TOPIC,
                            OrderEvent(
                                OrderEvent.TYPE_FAILED, orderId, o.userId,
                                o.status, o.totalAmount, "RECOVERED_STUCK_SAGA", Instant.now(),
                            ),
                        )
                    }
                }
            }
            meterRegistry.counter(
                "order.saga.recovery",
                Tags.of("result", if (summary.allCompensated) "ok" else "partial"),
            ).increment()
            log.warn(
                "recovered stuck order={} compensated={} failed={} exhausted={}",
                orderId, summary.compensated, summary.failed, summary.exhausted,
            )
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(SagaRecoveryJob::class.java)
    }
}
