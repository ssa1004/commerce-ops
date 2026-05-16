package io.minishop.order.service

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer
import io.minishop.order.concurrency.LimitExceededException
import io.minishop.order.domain.Order
import io.minishop.order.domain.OrderItem
import io.minishop.order.exception.OrchestrationException
import io.minishop.order.exception.OrchestrationException.Outcome
import io.minishop.order.exception.OrderNotFoundException
import io.minishop.order.kafka.dto.OrderEvent
import io.minishop.order.outbox.OutboxService
import io.minishop.order.repository.OrderRepository
import io.minishop.order.saga.OrderSagaCoordinator
import io.minishop.order.saga.OrderSagaEvents
import io.minishop.order.saga.OrderSagaStates
import io.minishop.order.web.dto.CreateOrderItemRequest
import io.minishop.order.web.dto.CreateOrderRequest
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.statemachine.StateMachine
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

/**
 * 주문 오케스트레이션. SAGA 패턴 (한 흐름이 여러 서비스에 걸쳐 있을 때, 중간에 실패하면
 * 앞단계를 되돌리는 보상 패턴) 의 단순 동기 버전 + Outbox 로 라이프사이클 이벤트 발행.
 *
 *   1. Order(PENDING) 저장 + outbox 에 OrderCreated 기록 (같은 트랜잭션 안에서)
 *   2. 각 item 에 대해 inventory-service.reserve 호출 (멱등 키: orderId+productId — 같은 키로
 *      두 번 와도 한 번만 차감되게 막음)
 *   3. payment-service.charge 호출
 *   4. 성공 → markPaid + outbox.OrderPaid (같은 트랜잭션)
 *      실패 → markFailed + outbox.OrderFailed + 잡아둔 재고 해제 (보상)
 *
 * Outbox: Order DB 변경과 같은 트랜잭션 안에서 outbox 테이블에 이벤트 행을 기록 →
 * poller (백그라운드 작업) 가 별도로 Kafka publish.
 * 이렇게 묶어두면 "Order 는 PAID 인데 Kafka 발행은 못 갔다" 는 부정합이 발생하지 않는다.
 */
@Service
class OrderService(
    private val orderRepository: OrderRepository,
    private val inventoryClient: InventoryClient,
    private val paymentClient: PaymentClient,
    private val outboxService: OutboxService,
    transactionTemplate: TransactionTemplate,
    private val meterRegistry: MeterRegistry,
    private val saga: OrderSagaCoordinator,
) {

    private val tx: TransactionTemplate = transactionTemplate

    fun create(request: CreateOrderRequest): Order {
        val sample = Timer.start(meterRegistry)

        val order: Order = tx.execute { _ ->
            val items = request.items.map { toItem(it) }
            val saved = orderRepository.save(Order.create(request.userId, items))
            outboxService.enqueue(
                "Order", saved.id!!, OrderEvent.TYPE_CREATED, OrderEvent.TOPIC,
                OrderEvent(
                    OrderEvent.TYPE_CREATED, saved.id, saved.userId,
                    saved.status, saved.totalAmount, null, Instant.now(),
                ),
            )
            saved
        }!!

        // Order.id 는 JPA save() 후 set 되는 var — Kotlin smart cast 의 promise 가 깨지므로
        // 한 번 캐시해서 non-null Long 으로 사용.
        val orderId: Long = order.id!!

        // SAGA shadow run — 기존 동기 흐름과 *병행* 으로 모델을 진행. 결정 일관성을 메트릭으로
        // 비교 (consistency=ok|mismatch). 모델 자체에 버그가 있어도 운영 트래픽엔 영향 없게
        // shadow 로 시작, 안정화되면 enforce 모드로 격상.
        val machine: StateMachine<OrderSagaStates, OrderSagaEvents> = saga.begin(orderId)

        val reserved: MutableList<ReservedItem> = ArrayList()
        try {
            reserveAll(order, request.items, reserved)
            saga.apply(machine, OrderSagaEvents.INVENTORY_OK)
            // RESERVED → CHARGING 진입 트리거. 결제 응답 (PAYMENT_OK / PAYMENT_DECLINED) 와는
            // 별도 이벤트로 분리해 모델이 결정적이게 (source 상태 + 이벤트로 transition 이 유일).
            saga.apply(machine, OrderSagaEvents.PAYMENT_CHARGE_STARTED)
            val payment = paymentClient.charge(orderId, order.userId!!, order.totalAmount!!)
            if (payment.isSuccess) {
                saga.apply(machine, OrderSagaEvents.PAYMENT_OK)
                markPaid(orderId)
                saga.assertConsistent(machine, null, true)
                recordOutcome(sample, "paid")
                return reload(orderId)
            }
            log.info("Payment declined for order {}: {}", orderId, payment.failureReason)
            saga.apply(machine, OrderSagaEvents.PAYMENT_DECLINED)
            compensate(orderId, reserved)
            saga.apply(machine, OrderSagaEvents.COMPENSATION_DONE)
            markFailed(orderId, "PAYMENT_DECLINED: ${payment.failureReason}")
            saga.assertConsistent(machine, Outcome.PAYMENT_DECLINED, false)
            recordOutcome(sample, "payment_declined")
            throw OrchestrationException(
                Outcome.PAYMENT_DECLINED, reload(orderId),
                "Payment declined: ${payment.failureReason}",
            )
        } catch (e: InventoryClient.OutOfStockException) {
            // 재고 부족 — SAGA 상 INVENTORY_RESERVING 단계 → FAILED (보상 없이 종결).
            saga.apply(machine, OrderSagaEvents.INVENTORY_OUT_OF_STOCK)
            compensate(orderId, reserved)
            markFailed(orderId, "OUT_OF_STOCK: ${e.message}")
            saga.assertConsistent(machine, Outcome.OUT_OF_STOCK, false)
            recordOutcome(sample, "out_of_stock")
            throw OrchestrationException(Outcome.OUT_OF_STOCK, reload(orderId), e.message ?: "")
        } catch (e: InventoryClient.InventoryInfraException) {
            saga.apply(machine, OrderSagaEvents.INVENTORY_INFRA_ERROR)
            compensate(orderId, reserved)
            saga.apply(machine, OrderSagaEvents.COMPENSATION_DONE)
            markFailed(orderId, "INVENTORY_INFRA: ${e.message}")
            saga.assertConsistent(machine, Outcome.INVENTORY_INFRA, false)
            recordOutcome(sample, "inventory_infra")
            throw OrchestrationException(Outcome.INVENTORY_INFRA, reload(orderId), e.message ?: "")
        } catch (e: PaymentClient.PaymentInfraException) {
            saga.apply(machine, OrderSagaEvents.PAYMENT_INFRA_ERROR)
            compensate(orderId, reserved)
            saga.apply(machine, OrderSagaEvents.COMPENSATION_DONE)
            markFailed(orderId, "PAYMENT_INFRA: ${e.message}")
            saga.assertConsistent(machine, Outcome.PAYMENT_INFRA, false)
            recordOutcome(sample, "payment_infra")
            throw OrchestrationException(Outcome.PAYMENT_INFRA, reload(orderId), e.message ?: "")
        } catch (e: LimitExceededException) {
            // adaptive limiter 가 inventory / payment 호출을 즉시 거절 — backend cascade 차단.
            // 우리는 *호출 자체를 안 한* 상태이므로 보상은 reserved 까지만 (이미 잡힌 재고 release).
            saga.apply(machine, OrderSagaEvents.UPSTREAM_LIMITED)
            compensate(orderId, reserved)
            saga.apply(machine, OrderSagaEvents.COMPENSATION_DONE)
            markFailed(orderId, "UPSTREAM_LIMITED[${e.upstream}]: ${e.message}")
            saga.assertConsistent(machine, Outcome.UPSTREAM_LIMITED, false)
            recordOutcome(sample, "upstream_limited")
            throw OrchestrationException(
                Outcome.UPSTREAM_LIMITED, reload(orderId),
                "upstream limited: ${e.upstream}",
            )
        }
    }

    @Transactional(readOnly = true)
    fun getById(id: Long): Order =
        orderRepository.findWithItemsById(id).orElseThrow { OrderNotFoundException(id) }

    /**
     * 의도적 N+1 데모 경로 (한 번의 마스터 조회 + N 번의 자식 조회가 일어나는 안티패턴).
     * fetch join 없이 listing 만 하면 응답 직렬화 시점에 각 Order 의 items 가 하나씩 lazy load 됨.
     * `slow-query-detector` 가 이 패턴을 자동 감지해 `n_plus_one_total` 카운터를 올린다.
     */
    @Transactional(readOnly = true)
    fun listRecent(size: Int): List<Order> =
        orderRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, size)).content

    private fun reserveAll(order: Order, items: List<CreateOrderItemRequest>, reservedAcc: MutableList<ReservedItem>) {
        for (item in items) {
            inventoryClient.reserve(item.productId, order.id!!, item.quantity)
            reservedAcc.add(ReservedItem(item.productId, item.quantity))
        }
    }

    private fun compensate(orderId: Long, reserved: List<ReservedItem>) {
        for (r in reserved) {
            inventoryClient.release(r.productId, orderId)
        }
    }

    private fun markPaid(orderId: Long) {
        tx.executeWithoutResult { _ ->
            orderRepository.findById(orderId).ifPresent { o ->
                o.markPaid()
                outboxService.enqueue(
                    "Order", orderId, OrderEvent.TYPE_PAID, OrderEvent.TOPIC,
                    OrderEvent(
                        OrderEvent.TYPE_PAID, orderId, o.userId,
                        o.status, o.totalAmount, null, Instant.now(),
                    ),
                )
            }
        }
    }

    private fun markFailed(orderId: Long, reason: String) {
        tx.executeWithoutResult { _ ->
            orderRepository.findById(orderId).ifPresent { o ->
                o.markFailed()
                outboxService.enqueue(
                    "Order", orderId, OrderEvent.TYPE_FAILED, OrderEvent.TOPIC,
                    OrderEvent(
                        OrderEvent.TYPE_FAILED, orderId, o.userId,
                        o.status, o.totalAmount, reason, Instant.now(),
                    ),
                )
            }
        }
    }

    private fun reload(orderId: Long): Order = tx.execute { _ ->
        orderRepository.findWithItemsById(orderId).orElseThrow()
    }!!

    private fun recordOutcome(sample: Timer.Sample, outcome: String) {
        sample.stop(meterRegistry.timer("order.orchestration", Tags.of("outcome", outcome)))
    }

    private fun toItem(req: CreateOrderItemRequest): OrderItem =
        OrderItem.of(req.productId, req.quantity, req.price)

    private data class ReservedItem(val productId: Long, val quantity: Int)

    companion object {
        private val log = LoggerFactory.getLogger(OrderService::class.java)
    }
}
