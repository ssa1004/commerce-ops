package io.minishop.inventory.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant

/**
 * 분산락(Redisson)으로 동시성을 제어하지만 안전망으로 JPA 낙관적 락(@Version)도 함께 둔다.
 * 락 timeout으로 동시 진입이 발생해도 DB 레벨에서 한 번 더 막힌다.
 *
 * Kotlin 마이그레이션 노트:
 * - JPA 가 reflect 로 setter 없이 필드를 채우므로 모든 필드 `var` + private set 으로 표현하되,
 *   getter 이름은 @get:JvmName 로 Java 호출자 (`getProductId()` 등) 와 100% 호환 유지.
 * - mutable aggregate — 일반 `class` (data class 가 아님). 기본 생성자는 `plugin.jpa` 가
 *   `@Entity` 에 noarg 생성자를 합성.
 */
@Entity
@Table(name = "inventories")
class Inventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @get:JvmName("getId")
    var id: Long? = null
        private set

    @Column(name = "product_id", nullable = false, unique = true)
    @get:JvmName("getProductId")
    var productId: Long? = null
        private set

    @Column(name = "available_quantity", nullable = false)
    @get:JvmName("getAvailableQuantity")
    var availableQuantity: Int? = null
        private set

    @Version
    @get:JvmName("getVersion")
    var version: Long? = null
        private set

    @Column(name = "created_at", nullable = false)
    @get:JvmName("getCreatedAt")
    var createdAt: Instant? = null
        private set

    @Column(name = "updated_at", nullable = false)
    @get:JvmName("getUpdatedAt")
    var updatedAt: Instant? = null
        private set

    fun canReserve(qty: Int): Boolean = (availableQuantity ?: 0) >= qty

    fun reserve(qty: Int) {
        check(canReserve(qty)) { "Insufficient stock for product $productId" }
        availableQuantity = (availableQuantity ?: 0) - qty
    }

    fun release(qty: Int) {
        availableQuantity = (availableQuantity ?: 0) + qty
    }

    @PrePersist
    internal fun onCreate() {
        val now = Instant.now()
        createdAt = now
        updatedAt = now
    }

    @PreUpdate
    internal fun onUpdate() {
        updatedAt = Instant.now()
    }
}
