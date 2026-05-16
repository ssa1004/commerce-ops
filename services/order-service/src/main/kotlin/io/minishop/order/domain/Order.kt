package io.minishop.order.domain

import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.OneToMany
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant

@Entity
@Table(name = "orders")
class Order protected constructor() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @get:JvmName("getId")
    var id: Long? = null
        private set

    @Column(name = "user_id", nullable = false)
    @get:JvmName("getUserId")
    var userId: Long? = null
        private set

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @get:JvmName("getStatus")
    var status: OrderStatus? = null
        private set

    @Column(name = "total_amount", nullable = false, precision = 15, scale = 2)
    @get:JvmName("getTotalAmount")
    var totalAmount: BigDecimal? = null
        private set

    @Column(name = "created_at", nullable = false)
    @get:JvmName("getCreatedAt")
    var createdAt: Instant? = null
        private set

    @Column(name = "updated_at", nullable = false)
    @get:JvmName("getUpdatedAt")
    var updatedAt: Instant? = null
        private set

    @OneToMany(mappedBy = "order", cascade = [CascadeType.ALL], orphanRemoval = true)
    @get:JvmName("getItems")
    var items: MutableList<OrderItem> = mutableListOf()
        private set

    fun addItem(item: OrderItem) {
        items.add(item)
        item.attachTo(this)
    }

    fun markPaid() {
        this.status = OrderStatus.PAID
    }

    fun markFailed() {
        this.status = OrderStatus.FAILED
    }

    fun cancel() {
        this.status = OrderStatus.CANCELLED
    }

    @PrePersist
    internal fun onCreate() {
        val now = Instant.now()
        this.createdAt = now
        this.updatedAt = now
    }

    @PreUpdate
    internal fun onUpdate() {
        this.updatedAt = Instant.now()
    }

    companion object {
        @JvmStatic
        fun create(userId: Long, items: List<OrderItem>): Order {
            val order = Order()
            order.userId = userId
            order.status = OrderStatus.PENDING
            items.forEach { order.addItem(it) }
            order.totalAmount = order.items
                .map { it.price!!.multiply(BigDecimal.valueOf(it.quantity!!.toLong())) }
                .fold(BigDecimal.ZERO, BigDecimal::add)
            return order
        }
    }
}
