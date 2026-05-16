package io.minishop.inventory.repository

import io.minishop.inventory.domain.InventoryReservation
import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

interface InventoryReservationRepository : JpaRepository<InventoryReservation, Long> {
    fun findByOrderIdAndProductId(orderId: Long, productId: Long): Optional<InventoryReservation>
}
