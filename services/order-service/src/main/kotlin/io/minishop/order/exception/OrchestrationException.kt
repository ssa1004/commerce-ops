package io.minishop.order.exception

import io.minishop.order.domain.Order

/**
 * 주문 처리 중 발생한 비즈니스 실패. 컨트롤러가 outcome에 맞춰 HTTP 상태 코드를 매핑한다.
 * 주문 자체는 이미 DB에 FAILED로 저장되어 있고, 보상(재고 release)도 끝난 상태.
 */
class OrchestrationException(
    outcome: Outcome,
    order: Order,
    message: String,
) : RuntimeException(message) {

    @get:JvmName("getOutcome")
    val outcome: Outcome = outcome

    @get:JvmName("getOrder")
    val order: Order = order

    enum class Outcome {
        OUT_OF_STOCK,           // 409 — 재고 부족
        PAYMENT_DECLINED,       // 402 — 결제 거절
        INVENTORY_INFRA,        // 503 — 재고 서비스 장애
        PAYMENT_INFRA,          // 502 — 결제 서비스 장애
        UPSTREAM_LIMITED,       // 503 — adaptive limiter 가 cascade 차단으로 즉시 거절
    }
}
