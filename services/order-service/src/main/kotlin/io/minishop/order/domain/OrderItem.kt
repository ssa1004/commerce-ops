package io.minishop.order.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.math.BigDecimal

@Entity
@Table(name = "order_items")
class OrderItem protected constructor() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @get:JvmName("getId")
    var id: Long? = null
        private set

    // JPA 양방향 관계 — 부모 Order 가 영속 컨텍스트에서 setter 로 붙임 (attachTo).
    @ManyToOne
    @JoinColumn(name = "order_id", nullable = false)
    private var order: Order? = null

    @Column(name = "product_id", nullable = false)
    @get:JvmName("getProductId")
    var productId: Long? = null
        private set

    @Column(nullable = false)
    @get:JvmName("getQuantity")
    var quantity: Int? = null
        private set

    @Column(nullable = false, precision = 15, scale = 2)
    @get:JvmName("getPrice")
    var price: BigDecimal? = null
        private set

    // 패키지-private 접근자 — Order.addItem 만 사용. Kotlin 의 internal 은 모듈 단위라 더 넓으므로
    // 같은 패키지에서만 호출되도록 @JvmName 없이 일반 fun (Java 도 같은 패키지 내에서 호출 가능).
    internal fun attachTo(order: Order) {
        this.order = order
    }

    companion object {
        @JvmStatic
        fun of(productId: Long, quantity: Int, price: BigDecimal): OrderItem {
            val item = OrderItem()
            item.productId = productId
            item.quantity = quantity
            item.price = price
            return item
        }
    }
}
