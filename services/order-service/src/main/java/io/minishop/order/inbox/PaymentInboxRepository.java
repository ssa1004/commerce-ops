package io.minishop.order.inbox;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentInboxRepository extends JpaRepository<PaymentInboxRecord, Long> {
    boolean existsByPaymentId(Long paymentId);
    Optional<PaymentInboxRecord> findByOrderId(Long orderId);
}
