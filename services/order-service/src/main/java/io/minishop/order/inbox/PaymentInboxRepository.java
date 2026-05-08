package io.minishop.order.inbox;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PaymentInboxRepository extends JpaRepository<PaymentInboxRecord, Long> {
    boolean existsByPaymentId(Long paymentId);
    Optional<PaymentInboxRecord> findByOrderId(Long orderId);

    /**
     * 정합성 점검용. eventType 과 receivedAt 으로 좁힌 뒤 페이징해 가져온다.
     * (예전엔 findAll() 후 메모리에서 필터링했는데, inbox 가 누적되면 OOM/지연 위험.)
     */
    List<PaymentInboxRecord> findByEventTypeAndReceivedAtAfterOrderByReceivedAtAsc(
            String eventType, Instant receivedAfter, Pageable pageable);
}
