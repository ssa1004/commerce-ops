package io.minishop.payment.dlq

/**
 * payment-service 에서 DLQ (Dead Letter Queue — 처리 실패 메시지가 격리되는 별도 토픽/저장소) 로
 * 격리될 수 있는 source 의 분류.
 *
 * - PAYMENT_CHARGE — `/payments` 의 PG 호출 (charge) 실패가 적재된 케이스. PG 가 timeout 이거나
 *   네트워크 blip 으로 결과를 알 수 없는 in-doubt 메시지 포함.
 * - PAYMENT_REFUND — 환불 (refund / reversal) 의 PG 호출 실패. charge 보다 한층 더 엄격한
 *   audit 가 요구된다 (돈 *돌려주는* 동작이라 중복 실행이 더 큰 사고).
 * - PG_CALLBACK — mock-pg 또는 실 PG 의 callback (webhook) 처리 실패. payload 검증 fail /
 *   서명 mismatch 등.
 * - OUTBOX — payment.events 발행 outbox 의 영구 실패 행 (status=FAILED).
 *
 * billing-platform ADR-0033 의 source 분리 패턴과 동일.
 */
enum class DlqSource {
    PAYMENT_CHARGE,
    PAYMENT_REFUND,
    PG_CALLBACK,
    OUTBOX,
}
