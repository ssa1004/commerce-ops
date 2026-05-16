package io.minishop.payment.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.PrePersist
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant

/**
 * 결제 aggregate. JPA entity 이므로 mutable + `private set` 패턴.
 *
 * Java 호출자 호환을 위해 모든 read accessor 는 `@get:JvmName("getXxx")` 로 record 가
 * 아닌 기존 bean-style getter 시그너처 (`payment.getId()` 등) 를 그대로 보존.
 * `pending(...)` factory 는 `@JvmStatic` 으로 companion 에 노출.
 */
@Entity
@Table(name = "payments")
class Payment protected constructor() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @get:JvmName("getId")
    var id: Long? = null
        private set

    @Column(name = "order_id", nullable = false)
    @get:JvmName("getOrderId")
    var orderId: Long? = null
        private set

    @Column(name = "user_id", nullable = false)
    @get:JvmName("getUserId")
    var userId: Long? = null
        private set

    @Column(nullable = false, precision = 15, scale = 2)
    @get:JvmName("getAmount")
    var amount: BigDecimal? = null
        private set

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @get:JvmName("getStatus")
    var status: PaymentStatus? = null
        private set

    @Column(name = "external_ref", length = 64)
    @get:JvmName("getExternalRef")
    var externalRef: String? = null
        private set

    @Column(name = "failure_reason", length = 255)
    @get:JvmName("getFailureReason")
    var failureReason: String? = null
        private set

    @Column(nullable = false)
    @get:JvmName("getAttempts")
    var attempts: Int? = null
        private set

    @Column(name = "created_at", nullable = false)
    @get:JvmName("getCreatedAt")
    var createdAt: Instant? = null
        private set

    @Column(name = "completed_at")
    @get:JvmName("getCompletedAt")
    var completedAt: Instant? = null
        private set

    fun markSuccess(externalRef: String) {
        this.status = PaymentStatus.SUCCESS
        this.externalRef = externalRef
        this.completedAt = Instant.now()
    }

    fun markFailed(reason: String) {
        this.status = PaymentStatus.FAILED
        this.failureReason = reason
        this.completedAt = Instant.now()
    }

    fun recordAttempt() {
        this.attempts = (this.attempts ?: 0) + 1
    }

    @PrePersist
    internal fun onCreate() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now()
        }
    }

    companion object {
        @JvmStatic
        fun pending(orderId: Long, userId: Long, amount: BigDecimal): Payment {
            val p = Payment()
            p.orderId = orderId
            p.userId = userId
            p.amount = amount
            p.status = PaymentStatus.PENDING
            p.attempts = 0
            return p
        }
    }
}
