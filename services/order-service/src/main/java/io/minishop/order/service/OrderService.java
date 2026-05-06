package io.minishop.order.service;

import io.minishop.order.domain.Order;
import io.minishop.order.domain.OrderItem;
import io.minishop.order.exception.OrderNotFoundException;
import io.minishop.order.repository.OrderRepository;
import io.minishop.order.web.dto.CreateOrderItemRequest;
import io.minishop.order.web.dto.CreateOrderRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class OrderService {

    private final OrderRepository orderRepository;

    public OrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Transactional
    public Order create(CreateOrderRequest request) {
        List<OrderItem> items = request.items().stream()
                .map(this::toItem)
                .toList();
        Order order = Order.create(request.userId(), items);
        return orderRepository.save(order);
    }

    @Transactional(readOnly = true)
    public Order getById(Long id) {
        return orderRepository.findWithItemsById(id)
                .orElseThrow(() -> new OrderNotFoundException(id));
    }

    private OrderItem toItem(CreateOrderItemRequest req) {
        return OrderItem.of(req.productId(), req.quantity(), req.price());
    }
}
