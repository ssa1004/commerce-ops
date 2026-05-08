package io.minishop.order.saga;

/**
 * 주문 SAGA 의 상태. 도메인 {@link io.minishop.order.domain.OrderStatus} 보다 *세분화* 되어
 * 있다. OrderStatus 는 사용자/외부 노출용 (PENDING/PAID/FAILED/CANCELLED 4가지) 인데, SAGA
 * 의 흐름은 그 사이의 *중간 상태* 를 명시해야 보상 (compensation) 의 진입/종료 시점을 정확히
 * 제어할 수 있다.
 *
 * <p>왜 별도 enum 으로 두는가:
 * <ul>
 *   <li>도메인 enum 은 *결과 표현* — 사용자가 `GET /orders/{id}` 에서 보는 값. 마이그레이션
 *       비용이 크다 (DB column / API 계약).</li>
 *   <li>SAGA enum 은 *진행 표현* — 코드 안에서 흐름을 모델링하는 데 쓰고, 도메인 OrderStatus
 *       로는 종결 시점에 매핑. 새 중간 상태 (예: AWAITING_PAYMENT_CONFIRMATION) 를 추가해도
 *       외부 계약은 영향 없음.</li>
 * </ul>
 *
 * <p>상태 의미:
 * <ul>
 *   <li>{@link #DRAFT}: 주문 생성 직후, 아직 어떤 외부 호출도 안 됐음.</li>
 *   <li>{@link #INVENTORY_RESERVING}: inventory.reserve 호출 중. 실패 시 INVENTORY_FAILED.</li>
 *   <li>{@link #INVENTORY_RESERVED}: 모든 item 재고 잡힘. 결제로 넘어갈 자격을 가짐.</li>
 *   <li>{@link #PAYMENT_CHARGING}: payment.charge 호출 중. 실패 시 PAYMENT_FAILED.</li>
 *   <li>{@link #COMPENSATING}: 결제 실패 후 잡아둔 재고 release 중. 보상 액션 진행 단계.</li>
 *   <li>{@link #PAID}: 종결 — 성공.</li>
 *   <li>{@link #FAILED}: 종결 — 어느 단계에서든 실패 + 보상 완료.</li>
 * </ul>
 */
public enum OrderSagaStates {
    DRAFT,
    INVENTORY_RESERVING,
    INVENTORY_RESERVED,
    PAYMENT_CHARGING,
    COMPENSATING,
    PAID,
    FAILED
}
