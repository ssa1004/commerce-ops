package io.minishop.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * 분산락(Redisson)으로 동시성을 제어하지만 안전망으로 JPA 낙관적 락(@Version)도 함께 둔다.
 * 락 timeout으로 동시 진입이 발생해도 DB 레벨에서 한 번 더 막힌다.
 */
@Entity
@Table(name = "inventories")
public class Inventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false, unique = true)
    private Long productId;

    @Column(name = "available_quantity", nullable = false)
    private Integer availableQuantity;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Inventory() {}

    public boolean canReserve(int qty) {
        return this.availableQuantity >= qty;
    }

    public void reserve(int qty) {
        if (!canReserve(qty)) {
            throw new IllegalStateException("Insufficient stock for product " + productId);
        }
        this.availableQuantity -= qty;
    }

    public void release(int qty) {
        this.availableQuantity += qty;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getProductId() { return productId; }
    public Integer getAvailableQuantity() { return availableQuantity; }
    public Long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
