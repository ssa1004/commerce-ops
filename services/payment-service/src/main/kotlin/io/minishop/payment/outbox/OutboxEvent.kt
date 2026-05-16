package io.minishop.payment.outbox

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.PrePersist
import jakarta.persistence.Table
import java.time.Instant

/**
 * Phase 3 Step 3a 준비 — payment-service 용 transactional outbox 엔티티 자리.
 *
 * order-service 의 [io.minishop.order.outbox.OutboxEvent] 와 같은 매핑. 현재는 어떤 코드
 * 경로도 이 엔티티를 INSERT 하지 않는다 — 테이블도 빈 상태로 유지되고, 매핑은 `ddl-auto=validate`
 * 의 컬럼 표류 감지 안전망으로만 둔다. 자세한 격상 계획은 패키지 doc ([io.minishop.payment.outbox]) 참고.
 *
 * Java 호환: bean-style getter (`outbox.getId()` 등) 를 `@get:JvmName` 으로 보존. `pending(...)`
 * factory 는 `@JvmStatic`.
 */
@Entity
@Table(name = "outbox_events")
class OutboxEvent protected constructor() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @get:JvmName("getId")
    var id: Long? = null
        private set

    @Column(name = "aggregate_type", nullable = false, length = 50)
    @get:JvmName("getAggregateType")
    var aggregateType: String? = null
        private set

    @Column(name = "aggregate_id", nullable = false)
    @get:JvmName("getAggregateId")
    var aggregateId: Long? = null
        private set

    @Column(name = "event_type", nullable = false, length = 50)
    @get:JvmName("getEventType")
    var eventType: String? = null
        private set

    @Column(nullable = false, length = 100)
    @get:JvmName("getTopic")
    var topic: String? = null
        private set

    @Column(nullable = false, columnDefinition = "TEXT")
    @get:JvmName("getPayload")
    var payload: String? = null
        private set

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @get:JvmName("getStatus")
    var status: OutboxStatus? = null
        private set

    @Column(nullable = false)
    @get:JvmName("getAttempts")
    var attempts: Int? = null
        private set

    @Column(name = "last_error", columnDefinition = "TEXT")
    @get:JvmName("getLastError")
    var lastError: String? = null
        private set

    @Column(name = "created_at", nullable = false)
    @get:JvmName("getCreatedAt")
    var createdAt: Instant? = null
        private set

    @Column(name = "sent_at")
    @get:JvmName("getSentAt")
    var sentAt: Instant? = null
        private set

    @PrePersist
    internal fun onCreate() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now()
        }
    }

    companion object {
        @JvmStatic
        fun pending(
            aggregateType: String,
            aggregateId: Long,
            eventType: String,
            topic: String,
            payload: String,
        ): OutboxEvent {
            val e = OutboxEvent()
            e.aggregateType = aggregateType
            e.aggregateId = aggregateId
            e.eventType = eventType
            e.topic = topic
            e.payload = payload
            e.status = OutboxStatus.PENDING
            e.attempts = 0
            return e
        }
    }
}
