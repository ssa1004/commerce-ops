package io.minishop.payment.outbox

/**
 * Phase 3 Step 3a 준비 — outbox_events.status 의 도메인 값. order-service 와 동일 의미:
 * - PENDING — 아직 발행 시도하지 않음 (또는 재시도 대기).
 * - SENT — Kafka 에서 ack 받은 후.
 * - FAILED — max-attempts 초과로 dead-letter 처리 (운영자 점검 필요).
 */
enum class OutboxStatus {
    PENDING,
    SENT,
    FAILED,
}
