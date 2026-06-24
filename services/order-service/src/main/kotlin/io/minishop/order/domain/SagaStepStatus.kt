package io.minishop.order.domain

/**
 * SAGA step 의 보상 관점 상태.
 *
 * - [STARTED]: 정방향 호출 직전 기록(의도 로그). 호출 결과를 아직 모르는 상태라 보상 대상.
 *   (이 상태로 남는 건 거의 *크래시* 뿐 — 호출이 동기적으로 끝나면 DONE/ABORTED 로 확정된다.)
 * - [DONE]: 정방향 호출 성공 확정 — 보상 대상.
 * - [ABORTED]: 정방향 호출이 동기적으로 실패(예: 재고 부족·한도 초과)해 *확정적으로* 아무것도
 *   안 일어남 — 보상 불필요(release 호출 자체를 안 함).
 * - [COMPENSATED]: 보상(역방향) 완료.
 * - [COMPENSATION_FAILED]: 보상 실패 — 복구 잡이 재시도, max 초과 시 운영자 신호.
 */
enum class SagaStepStatus {
    STARTED,
    DONE,
    ABORTED,
    COMPENSATED,
    COMPENSATION_FAILED,
}
