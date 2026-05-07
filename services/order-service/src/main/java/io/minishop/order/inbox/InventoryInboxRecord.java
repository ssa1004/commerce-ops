package io.minishop.order.inbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "inventory_inbox")
public class InventoryInboxRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reservation_id", nullable = false, unique = true)
    private Long reservationId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "raw_payload", nullable = false, columnDefinition = "TEXT")
    private String rawPayload;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected InventoryInboxRecord() {}

    public static InventoryInboxRecord of(Long reservationId, Long orderId, Long productId,
                                          String eventType, String status, String rawPayload) {
        InventoryInboxRecord r = new InventoryInboxRecord();
        r.reservationId = reservationId;
        r.orderId = orderId;
        r.productId = productId;
        r.eventType = eventType;
        r.status = status;
        r.rawPayload = rawPayload;
        return r;
    }

    @PrePersist
    void onCreate() { this.receivedAt = Instant.now(); }

    public Long getId() { return id; }
    public Long getReservationId() { return reservationId; }
    public Long getOrderId() { return orderId; }
    public Long getProductId() { return productId; }
    public String getEventType() { return eventType; }
    public String getStatus() { return status; }
    public Instant getReceivedAt() { return receivedAt; }
}
