package io.minishop.order.dlq

/**
 * order-service 에서 DLQ (Dead Letter Queue — 처리 실패 메시지가 격리되는 별도 토픽/저장소) 로
 * 격리될 수 있는 source 의 분류.
 *
 * 운영 도구 (Admin REST API) 가 list / replay / discard 의 대상 범위를 좁히는 1차 필터.
 * source 단위로 권한 / rate limit / bulk job 의 격리도가 결정되므로 enum 값 자체가 운영 계약.
 *
 * - ORDER_EVENT — 외부에 발행하는 OutboxPoller 가 격상한 비즈니스 이벤트
 *   (예: ORDER_CREATED / ORDER_PAID / ORDER_FAILED 등) 의 발행 실패.
 * - INVENTORY_INBOX — inventory.events consumer 가 inbox 저장 단계에서 실패한 메시지.
 * - PAYMENT_INBOX — payment.events consumer 가 inbox 저장 단계에서 실패한 메시지.
 * - SAGA — OrderSAGA (Spring StateMachine) 의 transition 실패 / guard 거절 등 SAGA 단계 실패.
 * - OUTBOX — outbox_events 의 status=FAILED 행 (max attempts 초과로 영구 격리된 행).
 *
 * ADR-0015 (notification) / ADR-0033 (billing) / ADR-0028 (market) / ADR-0026 (gpu) 의 source
 * 분리 패턴과 동일 — 한 번에 한 source 만 bulk 처리하도록 강제해 blast radius 를 좁힌다.
 */
enum class DlqSource {
    ORDER_EVENT,
    INVENTORY_INBOX,
    PAYMENT_INBOX,
    SAGA,
    OUTBOX,
}
