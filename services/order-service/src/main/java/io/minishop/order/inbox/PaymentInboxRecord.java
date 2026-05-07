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
@Table(name = "payment_inbox")
public class PaymentInboxRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false, unique = true)
    private Long paymentId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "external_ref", length = 64)
    private String externalRef;

    @Column(name = "raw_payload", nullable = false, columnDefinition = "TEXT")
    private String rawPayload;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected PaymentInboxRecord() {}

    public static PaymentInboxRecord of(Long paymentId, Long orderId, String eventType,
                                        String status, String externalRef, String rawPayload) {
        PaymentInboxRecord r = new PaymentInboxRecord();
        r.paymentId = paymentId;
        r.orderId = orderId;
        r.eventType = eventType;
        r.status = status;
        r.externalRef = externalRef;
        r.rawPayload = rawPayload;
        return r;
    }

    @PrePersist
    void onCreate() { this.receivedAt = Instant.now(); }

    public Long getId() { return id; }
    public Long getPaymentId() { return paymentId; }
    public Long getOrderId() { return orderId; }
    public String getEventType() { return eventType; }
    public String getStatus() { return status; }
    public String getExternalRef() { return externalRef; }
    public Instant getReceivedAt() { return receivedAt; }
}
