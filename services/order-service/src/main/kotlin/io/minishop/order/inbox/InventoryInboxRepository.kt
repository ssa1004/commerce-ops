package io.minishop.order.inbox

import org.springframework.data.jpa.repository.JpaRepository

interface InventoryInboxRepository : JpaRepository<InventoryInboxRecord, Long> {
    fun existsByReservationId(reservationId: Long): Boolean
    fun findByOrderId(orderId: Long): List<InventoryInboxRecord>
}
