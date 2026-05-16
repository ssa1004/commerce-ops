package io.minishop.order.saga

/**
 * SAGA 의 *입력 트리거* — 외부 (서비스) 의 응답이 어떤 형태로 들어오느냐를 표현.
 * StateMachine 은 현재 상태에서 들어온 이벤트가 정의된 transition 인지 (가드 통과 포함)
 * 만 평가하므로, 가능한 응답 종류를 *모두* 이벤트로 표현해 *불완전한 모델* 을 컴파일러가
 * 잡아낼 수 있게 한다.
 *
 * 이벤트 분류:
 * - [ORDER_CREATED]: DRAFT 에서 SAGA 시작 트리거.
 * - [INVENTORY_OK] / [INVENTORY_OUT_OF_STOCK] / [INVENTORY_INFRA_ERROR]:
 *   재고 호출 결과 3가지. OUT_OF_STOCK 은 비즈니스 실패 (4xx-equivalent), INFRA_ERROR 는
 *   인프라 장애 (5xx-equivalent) — 보상 / retry 정책이 달라질 여지가 있어 분리.
 * - [PAYMENT_OK] / [PAYMENT_DECLINED] / [PAYMENT_INFRA_ERROR]: 결제 결과.
 *   DECLINED (카드 거절 등) 와 INFRA_ERROR (PG 호출 자체 실패) 도 같은 이유로 분리.
 * - [COMPENSATION_DONE]: 보상 완료 — COMPENSATING 에서 FAILED 로의 종결 트리거.
 * - [UPSTREAM_LIMITED]: adaptive limiter 가 호출 자체를 거절한 경우. 이 시점엔
 *   upstream 호출이 *시작도 안 됐으므로* 보상 대상이 그 직전 단계에 잡힌 재고 뿐 — SAGA
 *   관점에선 일반 INFRA 실패와 같은 분기로 합류하지만 메트릭/로그 분류는 분리.
 */
enum class OrderSagaEvents {
    ORDER_CREATED,
    INVENTORY_OK,
    INVENTORY_OUT_OF_STOCK,
    INVENTORY_INFRA_ERROR,

    /** 결제 호출 시작 — RESERVED → CHARGING. 응답 OK 와 분리되어 transition 이 명확. */
    PAYMENT_CHARGE_STARTED,
    PAYMENT_OK,
    PAYMENT_DECLINED,
    PAYMENT_INFRA_ERROR,
    COMPENSATION_DONE,
    UPSTREAM_LIMITED,
}
