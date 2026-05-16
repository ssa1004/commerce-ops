package io.minishop.order.outbox

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.Optional

interface OutboxRepository : JpaRepository<OutboxEvent, Long> {

    /**
     * 폴러가 다음 발행할 한 행을 잡는 쿼리. 호출자는 반드시 트랜잭션 안에서 호출해야 한다 (`FOR
     * UPDATE` 가 의미를 가지려면 트랜잭션이 필요하기 때문).
     *
     * `FOR UPDATE SKIP LOCKED` — PostgreSQL 옵션. 다른 트랜잭션이 잡고 있는 행은
     * 건너뛰고 다음 행을 본다. 여러 폴러 인스턴스가 동시에 돌아도 같은 행을 두 번 처리하지 않게 만드는
     * 핵심 트릭.
     *
     * `id ASC` 를 tie-break 로 둔 이유:
     * - `created_at` 은 NOW() 기본 해상도 (마이크로초) 안에서 같은 millisecond 에 여러 행이
     *   들어가면 동률이 생긴다.
     * - 동률만 있으면 PostgreSQL 이 임의 순서로 돌려줄 수 있어 발행 순서가 비결정적이 된다.
     * - 다운스트림이 같은 aggregate 의 이벤트 순서를 가정하면 (예: 같은 주문의 Created → Paid → Failed)
     *   이 비결정성이 사고를 만든다.
     * - `id ASC` 는 단조 증가라 동률을 결정적으로 깬다.
     */
    @Query(
        value = "SELECT * FROM outbox_events WHERE status = 'PENDING' " +
            "ORDER BY created_at ASC, id ASC LIMIT 1 FOR UPDATE SKIP LOCKED",
        nativeQuery = true,
    )
    fun findNextPendingForUpdate(): Optional<OutboxEvent>
}
