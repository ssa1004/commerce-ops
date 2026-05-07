package io.minishop.order.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * 폴러가 동시에 여러 인스턴스에서 돌 때 같은 행을 두 번 처리하지 않도록
     * SELECT ... FOR UPDATE SKIP LOCKED를 명시적으로 사용한다 (PostgreSQL).
     * 호출자는 트랜잭션 안에서 사용해야 한다.
     */
    @Query(
            value = "SELECT * FROM outbox_events WHERE status = 'PENDING' " +
                    "ORDER BY created_at ASC LIMIT :limit FOR UPDATE SKIP LOCKED",
            nativeQuery = true
    )
    List<OutboxEvent> findPendingForUpdate(@Param("limit") int limit);
}
