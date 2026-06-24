package io.minishop.order.domain

/**
 * SAGA step 의 보상 관점 상태.
 *
 * - [STARTED]: 정방향 호출 직전 기록(의도 로그). 원격 호출 성공 여부와 무관하게 보상 대상.
 * - [DONE]: 정방향 호출 성공 확정.
 * - [COMPENSATED]: 보상(역방향) 완료.
 * - [COMPENSATION_FAILED]: 보상 실패 — 복구 잡이 재시도, max 초과 시 운영자 신호.
 */
enum class SagaStepStatus {
    STARTED,
    DONE,
    COMPENSATED,
    COMPENSATION_FAILED,
}
