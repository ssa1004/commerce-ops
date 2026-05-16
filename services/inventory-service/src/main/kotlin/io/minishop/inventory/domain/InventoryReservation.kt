package io.minishop.inventory.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.PrePersist
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

/**
 * 동일 (orderId, productId)의 reserve/release를 멱등하게 만든다.
 * - reserve: unique 제약 위반 시 이미 예약된 것으로 판단
 * - release: status 확인 후 RELEASED인 경우 no-op
 *
 * Kotlin 마이그레이션 노트:
 * - mutable aggregate 패턴: 일반 `class` + `private set` + `@get:JvmName` 으로 Java 측
 *   `getId()` / `getStatus()` 등 동일 시그니처 유지. 생성은 companion `@JvmStatic reserve(...)` 팩토리.
 */
@Entity
@Table(
    name = "inventory_reservations",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_reservation_order_product",
            columnNames = ["order_id", "product_id"],
        ),
    ],
)
class InventoryReservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @get:JvmName("getId")
    var id: Long? = null
        private set

    @Column(name = "product_id", nullable = false)
    @get:JvmName("getProductId")
    var productId: Long? = null
        private set

    @Column(name = "order_id", nullable = false)
    @get:JvmName("getOrderId")
    var orderId: Long? = null
        private set

    @Column(nullable = false)
    @get:JvmName("getQuantity")
    var quantity: Int? = null
        private set

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @get:JvmName("getStatus")
    var status: ReservationStatus? = null
        private set

    @Column(name = "created_at", nullable = false)
    @get:JvmName("getCreatedAt")
    var createdAt: Instant? = null
        private set

    @Column(name = "released_at")
    @get:JvmName("getReleasedAt")
    var releasedAt: Instant? = null
        private set

    fun release() {
        if (status == ReservationStatus.RELEASED) return
        status = ReservationStatus.RELEASED
        releasedAt = Instant.now()
    }

    fun isReleased(): Boolean = status == ReservationStatus.RELEASED

    @PrePersist
    internal fun onCreate() {
        if (createdAt == null) createdAt = Instant.now()
    }

    companion object {
        @JvmStatic
        fun reserve(productId: Long, orderId: Long, quantity: Int): InventoryReservation =
            InventoryReservation().apply {
                this.productId = productId
                this.orderId = orderId
                this.quantity = quantity
                this.status = ReservationStatus.RESERVED
            }
    }
}
