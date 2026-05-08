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
     *
     * 정렬에 {@code id ASC} 도 함께 둔 이유: {@code created_at} 은 NOW() 의 기본 해상도가
     * 마이크로초라 같은 트랜잭션이나 같은 millisecond 안에 여러 행이 들어가면 동률이 생긴다.
     * 동률만으로는 PostgreSQL 이 임의 순서로 돌려줄 수 있어 폴러 사이/실행 사이 순서가 비결정적이
     * 된다. 같은 aggregate 안에서의 이벤트 순서가 보존되어야 하는 다운스트림 (예: 같은 주문에 대한
     * Created → Paid → Failed) 에서 이 비결정성이 사고를 만들 수 있어 명시적 tie-break 를 둔다.
     */
    @Query(
            value = "SELECT * FROM outbox_events WHERE status = 'PENDING' " +
                    "ORDER BY created_at ASC, id ASC LIMIT :limit FOR UPDATE SKIP LOCKED",
            nativeQuery = true
    )
    List<OutboxEvent> findPendingForUpdate(@Param("limit") int limit);
}
