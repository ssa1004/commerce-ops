package io.minishop.payment.dlq

import java.time.Instant

/**
 * payment-service 의 DLQ 한 건. order-service 와 동일 모양 (콘솔 UI 호환), service 특유 필드는
 * [paymentId] / [customerId] / `idempotencyKey` (헤더로 전달).
 *
 * billing ADR-0033 의 `Idempotency-Key` 헤더 패턴 — replay 시 동일 키를 그대로 복사해 PG 가 같은
 * 결제로 인식. headers map 에 `Idempotency-Key` 키가 그대로 들어 있어야 한다.
 */
data class DlqMessage(
    val messageId: String,
    val source: DlqSource,
    val topic: String,
    val paymentId: Long?,
    val customerId: Long?,
    val errorType: String,
    val errorMessage: String,
    val payload: String,
    val headers: Map<String, String>,
    val firstFailedAt: Instant,
    val lastFailedAt: Instant,
    val attempts: Int,
)
