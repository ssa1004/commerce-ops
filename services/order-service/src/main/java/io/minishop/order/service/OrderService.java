package io.minishop.order.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.minishop.order.domain.Order;
import io.minishop.order.domain.OrderItem;
import io.minishop.order.exception.OrchestrationException;
import io.minishop.order.exception.OrchestrationException.Outcome;
import io.minishop.order.exception.OrderNotFoundException;
import io.minishop.order.kafka.dto.OrderEvent;
import io.minishop.order.outbox.OutboxService;
import io.minishop.order.repository.OrderRepository;
import io.minishop.order.web.dto.CreateOrderItemRequest;
import io.minishop.order.web.dto.CreateOrderRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 주문 오케스트레이션. SAGA 패턴의 단순 동기 버전 + Outbox로 lifecycle 이벤트 발행.
 *
 *   1. Order(PENDING) 저장 + outbox에 OrderCreated 기록 (같은 트랜잭션)
 *   2. 각 item에 대해 inventory-service.reserve 호출 (멱등 키: orderId+productId)
 *   3. payment-service.charge 호출
 *   4. 성공 → markPaid + outbox.OrderPaid (같은 트랜잭션)
 *      실패 → markFailed + outbox.OrderFailed + 재고 보상 release
 *
 * Outbox: Order DB 변경과 같은 TX 안에 이벤트 행을 기록 → poller가 별도 Kafka publish.
 * 이렇게 묶어두면 "Order는 PAID인데 Kafka에는 못 갔다"는 부정합이 발생하지 않는다.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final InventoryClient inventoryClient;
    private final PaymentClient paymentClient;
    private final OutboxService outboxService;
    private final TransactionTemplate tx;
    private final MeterRegistry meterRegistry;

    public OrderService(OrderRepository orderRepository,
                        InventoryClient inventoryClient,
                        PaymentClient paymentClient,
                        OutboxService outboxService,
                        TransactionTemplate transactionTemplate,
                        MeterRegistry meterRegistry) {
        this.orderRepository = orderRepository;
        this.inventoryClient = inventoryClient;
        this.paymentClient = paymentClient;
        this.outboxService = outboxService;
        this.tx = transactionTemplate;
        this.meterRegistry = meterRegistry;
    }

    public Order create(CreateOrderRequest request) {
        Timer.Sample sample = Timer.start(meterRegistry);

        Order order = tx.execute(s -> {
            List<OrderItem> items = request.items().stream().map(this::toItem).toList();
            Order saved = orderRepository.save(Order.create(request.userId(), items));
            outboxService.enqueue("Order", saved.getId(), OrderEvent.TYPE_CREATED, OrderEvent.TOPIC,
                    new OrderEvent(OrderEvent.TYPE_CREATED, saved.getId(), saved.getUserId(),
                            saved.getStatus(), saved.getTotalAmount(), null, Instant.now()));
            return saved;
        });

        List<ReservedItem> reserved = new ArrayList<>();
        try {
            reserveAll(order, request.items(), reserved);
            PaymentClient.PaymentResult payment = paymentClient.charge(
                    order.getId(), order.getUserId(), order.getTotalAmount()
            );
            if (payment.isSuccess()) {
                markPaid(order.getId());
                recordOutcome(sample, "paid");
                return reload(order.getId());
            }
            log.info("Payment declined for order {}: {}", order.getId(), payment.failureReason());
            compensate(order.getId(), reserved);
            markFailed(order.getId(), "PAYMENT_DECLINED: " + payment.failureReason());
            recordOutcome(sample, "payment_declined");
            throw new OrchestrationException(Outcome.PAYMENT_DECLINED, reload(order.getId()),
                    "Payment declined: " + payment.failureReason());

        } catch (InventoryClient.OutOfStockException e) {
            compensate(order.getId(), reserved);
            markFailed(order.getId(), "OUT_OF_STOCK: " + e.getMessage());
            recordOutcome(sample, "out_of_stock");
            throw new OrchestrationException(Outcome.OUT_OF_STOCK, reload(order.getId()), e.getMessage());

        } catch (InventoryClient.InventoryInfraException e) {
            compensate(order.getId(), reserved);
            markFailed(order.getId(), "INVENTORY_INFRA: " + e.getMessage());
            recordOutcome(sample, "inventory_infra");
            throw new OrchestrationException(Outcome.INVENTORY_INFRA, reload(order.getId()), e.getMessage());

        } catch (PaymentClient.PaymentInfraException e) {
            compensate(order.getId(), reserved);
            markFailed(order.getId(), "PAYMENT_INFRA: " + e.getMessage());
            recordOutcome(sample, "payment_infra");
            throw new OrchestrationException(Outcome.PAYMENT_INFRA, reload(order.getId()), e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public Order getById(Long id) {
        return orderRepository.findWithItemsById(id)
                .orElseThrow(() -> new OrderNotFoundException(id));
    }

    private void reserveAll(Order order, List<CreateOrderItemRequest> items, List<ReservedItem> reservedAcc) {
        for (CreateOrderItemRequest item : items) {
            inventoryClient.reserve(item.productId(), order.getId(), item.quantity());
            reservedAcc.add(new ReservedItem(item.productId(), item.quantity()));
        }
    }

    private void compensate(Long orderId, List<ReservedItem> reserved) {
        for (ReservedItem r : reserved) {
            inventoryClient.release(r.productId(), orderId);
        }
    }

    private void markPaid(Long orderId) {
        tx.executeWithoutResult(s -> orderRepository.findById(orderId).ifPresent(o -> {
            o.markPaid();
            outboxService.enqueue("Order", orderId, OrderEvent.TYPE_PAID, OrderEvent.TOPIC,
                    new OrderEvent(OrderEvent.TYPE_PAID, orderId, o.getUserId(),
                            o.getStatus(), o.getTotalAmount(), null, Instant.now()));
        }));
    }

    private void markFailed(Long orderId, String reason) {
        tx.executeWithoutResult(s -> orderRepository.findById(orderId).ifPresent(o -> {
            o.markFailed();
            outboxService.enqueue("Order", orderId, OrderEvent.TYPE_FAILED, OrderEvent.TOPIC,
                    new OrderEvent(OrderEvent.TYPE_FAILED, orderId, o.getUserId(),
                            o.getStatus(), o.getTotalAmount(), reason, Instant.now()));
        }));
    }

    private Order reload(Long orderId) {
        return tx.execute(s -> orderRepository.findWithItemsById(orderId).orElseThrow());
    }

    private void recordOutcome(Timer.Sample sample, String outcome) {
        sample.stop(meterRegistry.timer("order.orchestration", Tags.of("outcome", outcome)));
    }

    private OrderItem toItem(CreateOrderItemRequest req) {
        return OrderItem.of(req.productId(), req.quantity(), req.price());
    }

    private record ReservedItem(Long productId, Integer quantity) {}
}
