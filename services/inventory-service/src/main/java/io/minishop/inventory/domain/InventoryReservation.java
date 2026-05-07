package io.minishop.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * 동일 (orderId, productId)의 reserve/release를 멱등하게 만든다.
 * - reserve: unique 제약 위반 시 이미 예약된 것으로 판단
 * - release: status 확인 후 RELEASED인 경우 no-op
 */
@Entity
@Table(name = "inventory_reservations",
        uniqueConstraints = @UniqueConstraint(name = "uq_reservation_order_product",
                columnNames = {"order_id", "product_id"}))
public class InventoryReservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(nullable = false)
    private Integer quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "released_at")
    private Instant releasedAt;

    protected InventoryReservation() {}

    public static InventoryReservation reserve(Long productId, Long orderId, Integer quantity) {
        InventoryReservation r = new InventoryReservation();
        r.productId = productId;
        r.orderId = orderId;
        r.quantity = quantity;
        r.status = ReservationStatus.RESERVED;
        return r;
    }

    public void release() {
        if (this.status == ReservationStatus.RELEASED) return;
        this.status = ReservationStatus.RELEASED;
        this.releasedAt = Instant.now();
    }

    public boolean isReleased() {
        return this.status == ReservationStatus.RELEASED;
    }

    @PrePersist
    void onCreate() {
        if (this.createdAt == null) this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getProductId() { return productId; }
    public Long getOrderId() { return orderId; }
    public Integer getQuantity() { return quantity; }
    public ReservationStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getReleasedAt() { return releasedAt; }
}
