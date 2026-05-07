package io.minishop.order.inbox;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InventoryInboxRepository extends JpaRepository<InventoryInboxRecord, Long> {
    boolean existsByReservationId(Long reservationId);
    List<InventoryInboxRecord> findByOrderId(Long orderId);
}
