package io.minishop.payment.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Phase 3 Step 3a 준비 — payment-service 용 transactional outbox 엔티티 자리.
 *
 * <p>order-service 의 {@code io.minishop.order.outbox.OutboxEvent} 와 같은 매핑.
 * 현재는 어떤 코드 경로도 이 엔티티를 INSERT 하지 않는다 — 테이블도 빈 상태로 유지되고,
 * 매핑은 ddl-auto=validate 의 컬럼 표류 감지 안전망으로만 둔다. 자세한 격상 계획은
 * {@link io.minishop.payment.outbox 패키지 javadoc} 참고.
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "aggregate_type", nullable = false, length = 50)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private Long aggregateId;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(nullable = false, length = 100)
    private String topic;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutboxStatus status;

    @Column(nullable = false)
    private Integer attempts;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    protected OutboxEvent() {}

    public static OutboxEvent pending(String aggregateType, Long aggregateId, String eventType, String topic, String payload) {
        OutboxEvent e = new OutboxEvent();
        e.aggregateType = aggregateType;
        e.aggregateId = aggregateId;
        e.eventType = eventType;
        e.topic = topic;
        e.payload = payload;
        e.status = OutboxStatus.PENDING;
        e.attempts = 0;
        return e;
    }

    @PrePersist
    void onCreate() {
        if (this.createdAt == null) this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getAggregateType() { return aggregateType; }
    public Long getAggregateId() { return aggregateId; }
    public String getEventType() { return eventType; }
    public String getTopic() { return topic; }
    public String getPayload() { return payload; }
    public OutboxStatus getStatus() { return status; }
    public Integer getAttempts() { return attempts; }
    public String getLastError() { return lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getSentAt() { return sentAt; }
}
