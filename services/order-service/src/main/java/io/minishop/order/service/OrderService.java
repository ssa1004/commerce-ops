package io.minishop.order.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.minishop.order.domain.Order;
import io.minishop.order.domain.OrderItem;
import io.minishop.order.exception.OrchestrationException;
import io.minishop.order.exception.OrchestrationException.Outcome;
import io.minishop.order.exception.OrderNotFoundException;
import io.minishop.order.repository.OrderRepository;
import io.minishop.order.web.dto.CreateOrderItemRequest;
import io.minishop.order.web.dto.CreateOrderRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * 주문 오케스트레이션. SAGA 패턴의 단순 동기 버전.
 *
 *   1. Order(PENDING) 저장
 *   2. 각 item에 대해 inventory-service.reserve 호출 (멱등 키: orderId+productId)
 *      재고 부족이면 즉시 중단하고 이미 예약된 것들을 release (보상)
 *   3. payment-service.charge 호출
 *      성공 → Order(PAID), 실패 → 모든 재고 release + Order(FAILED)
 *
 * 멱등성:
 *   - inventory reserve/release는 (orderId, productId) 단위 멱등 → 같은 orderId로 재시도해도 안전
 *   - payment는 orderId가 멱등 키 (Phase 2에서 강화 예정)
 *
 * Phase 2에서는 이 흐름을 Kafka 이벤트 + Outbox 패턴으로 전환.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final InventoryClient inventoryClient;
    private final PaymentClient paymentClient;
    private final TransactionTemplate tx;
    private final MeterRegistry meterRegistry;

    public OrderService(OrderRepository orderRepository,
                        InventoryClient inventoryClient,
                        PaymentClient paymentClient,
                        TransactionTemplate transactionTemplate,
                        MeterRegistry meterRegistry) {
        this.orderRepository = orderRepository;
        this.inventoryClient = inventoryClient;
        this.paymentClient = paymentClient;
        this.tx = transactionTemplate;
        this.meterRegistry = meterRegistry;
    }

    public Order create(CreateOrderRequest request) {
        Timer.Sample sample = Timer.start(meterRegistry);

        Order order = tx.execute(s -> {
            List<OrderItem> items = request.items().stream().map(this::toItem).toList();
            return orderRepository.save(Order.create(request.userId(), items));
        });

        List<ReservedItem> reserved = new ArrayList<>();
        try {
            reserveAll(order, request.items(), reserved);
            PaymentClient.PaymentResult payment = paymentClient.charge(
                    order.getId(), order.getUserId(), order.getTotalAmount()
            );
            if (payment.isSuccess()) {
                tx.executeWithoutResult(s -> orderRepository.findById(order.getId())
                        .ifPresent(Order::markPaid));
                recordOutcome(sample, "paid");
                return reload(order.getId());
            }
            // PG 거절 — 재고 보상 + 주문 FAILED
            log.info("Payment declined for order {}: {}", order.getId(), payment.failureReason());
            compensate(order.getId(), reserved);
            failOrder(order.getId());
            recordOutcome(sample, "payment_declined");
            throw new OrchestrationException(Outcome.PAYMENT_DECLINED, reload(order.getId()),
                    "Payment declined: " + payment.failureReason());

        } catch (InventoryClient.OutOfStockException e) {
            compensate(order.getId(), reserved);
            failOrder(order.getId());
            recordOutcome(sample, "out_of_stock");
            throw new OrchestrationException(Outcome.OUT_OF_STOCK, reload(order.getId()), e.getMessage());

        } catch (InventoryClient.InventoryInfraException e) {
            compensate(order.getId(), reserved);
            failOrder(order.getId());
            recordOutcome(sample, "inventory_infra");
            throw new OrchestrationException(Outcome.INVENTORY_INFRA, reload(order.getId()), e.getMessage());

        } catch (PaymentClient.PaymentInfraException e) {
            compensate(order.getId(), reserved);
            failOrder(order.getId());
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

    private void failOrder(Long orderId) {
        tx.executeWithoutResult(s -> orderRepository.findById(orderId)
                .ifPresent(Order::markFailed));
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
