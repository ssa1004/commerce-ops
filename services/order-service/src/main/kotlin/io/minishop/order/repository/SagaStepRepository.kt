package io.minishop.order.repository

import io.minishop.order.domain.SagaStep
import org.springframework.data.jpa.repository.JpaRepository

interface SagaStepRepository : JpaRepository<SagaStep, Long> {

    /**
     * 한 주문의 step 을 seq 역순으로. 보상은 정방향의 역순이어야 하므로 desc.
     * (단계가 적어 전부 로드 — 단계가 크게 늘면 not-COMPENSATED 만 가져오는 쿼리로 좁힐 것.)
     */
    fun findByOrderIdOrderBySeqDesc(orderId: Long): List<SagaStep>
}
