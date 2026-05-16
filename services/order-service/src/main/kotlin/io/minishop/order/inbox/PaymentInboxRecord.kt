package io.minishop.order.inbox

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.PrePersist
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "payment_inbox")
class PaymentInboxRecord protected constructor() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @get:JvmName("getId")
    var id: Long? = null
        private set

    @Column(name = "payment_id", nullable = false, unique = true)
    @get:JvmName("getPaymentId")
    var paymentId: Long? = null
        private set

    @Column(name = "order_id", nullable = false)
    @get:JvmName("getOrderId")
    var orderId: Long? = null
        private set

    @Column(name = "event_type", nullable = false, length = 50)
    @get:JvmName("getEventType")
    var eventType: String? = null
        private set

    @Column(nullable = false, length = 20)
    @get:JvmName("getStatus")
    var status: String? = null
        private set

    @Column(name = "external_ref", length = 64)
    @get:JvmName("getExternalRef")
    var externalRef: String? = null
        private set

    @Column(name = "raw_payload", nullable = false, columnDefinition = "TEXT")
    var rawPayload: String? = null
        private set

    @Column(name = "received_at", nullable = false)
    @get:JvmName("getReceivedAt")
    var receivedAt: Instant? = null
        private set

    @PrePersist
    internal fun onCreate() {
        this.receivedAt = Instant.now()
    }

    companion object {
        @JvmStatic
        fun of(
            paymentId: Long,
            orderId: Long,
            eventType: String,
            status: String,
            externalRef: String?,
            rawPayload: String,
        ): PaymentInboxRecord {
            val r = PaymentInboxRecord()
            r.paymentId = paymentId
            r.orderId = orderId
            r.eventType = eventType
            r.status = status
            r.externalRef = externalRef
            r.rawPayload = rawPayload
            return r
        }
    }
}
