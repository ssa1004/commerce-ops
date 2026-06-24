package io.minishop.order.saga

/**
 * SAGA step / 보상 액션 이름 상수. saga_step 테이블의 step_name / compensation 컬럼 값.
 *
 * 현재 보상 가능한 정방향 단계는 재고 예약 하나뿐이다. 쿠폰/포인트/배송 같은 단계가 추가되면
 * 여기에 (STEP, COMPENSATION) 쌍을 늘리고 [CompensationRunner.dispatch] 에 핸들러를 추가한다.
 */
object SagaSteps {
    const val INVENTORY_RESERVE = "INVENTORY_RESERVE"
    const val INVENTORY_RELEASE = "INVENTORY_RELEASE"
}
