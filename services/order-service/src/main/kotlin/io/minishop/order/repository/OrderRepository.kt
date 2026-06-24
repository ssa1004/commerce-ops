package io.minishop.order.repository

import io.minishop.order.domain.Order
import io.minishop.order.domain.OrderStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.Optional

interface OrderRepository : JpaRepository<Order, Long> {

    /**
     * Detail view 용도. items까지 한 쿼리로 fetch — N+1 회피.
     */
    @EntityGraph(attributePaths = ["items"])
    fun findWithItemsById(id: Long): Optional<Order>

    /**
     * 의도적으로 *naive* 한 listing 메서드 — items는 lazy.
     * 응답 직렬화에서 order.getItems()가 호출되면 order당 SELECT가 한 번씩 추가로 나간다 — 전형적 N+1.
     *
     * `slow-query-detector` 모듈이 이걸 자동 감지해 `n_plus_one_total` 카운터를 증가시키는 시연용 경로.
     * 운영에서는 detail 쿼리처럼 EntityGraph 또는 fetch join을 써야 한다.
     */
    fun findAllByOrderByCreatedAtDesc(pageable: Pageable): Page<Order>

    /**
     * 멈춘(stuck) 주문 — 일정 시각 이전에 만들어졌는데 아직 PENDING. 정상 흐름은 동기라
     * 즉시 PAID/FAILED 로 끝나므로, 오래 PENDING 이면 프로세스 크래시로 멈춘 것. [io.minishop.order.saga.SagaRecoveryJob] 용.
     */
    fun findByStatusAndCreatedAtBefore(status: OrderStatus, createdAt: Instant, pageable: Pageable): Page<Order>
}
