package io.minishop.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;

@Entity
@Table(name = "order_items")
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(nullable = false)
    private Integer quantity;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal price;

    protected OrderItem() {}

    public static OrderItem of(Long productId, Integer quantity, BigDecimal price) {
        OrderItem item = new OrderItem();
        item.productId = productId;
        item.quantity = quantity;
        item.price = price;
        return item;
    }

    void attachTo(Order order) {
        this.order = order;
    }

    public Long getId() { return id; }
    public Long getProductId() { return productId; }
    public Integer getQuantity() { return quantity; }
    public BigDecimal getPrice() { return price; }
}
