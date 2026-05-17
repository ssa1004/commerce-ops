package io.minishop.payment.dlq

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * payment-service 특유 단건 액션 구현 — PG 결제 멱등성 (`Idempotency-Key`) 가 핵심.
 *
 * replay 시:
 * 1. 원 메시지 headers 에서 `Idempotency-Key` 추출 (없으면 messageId 기반으로 결정적 키 생성).
 * 2. PG 재호출 — 같은 키는 PG 가 같은 결제로 인식해 두 번 차감되지 않음 (billing 패턴).
 * 3. 응답의 `idempotencyKey` 필드에 사용한 키를 노출 — 콘솔이 PG audit 와 매칭 가능.
 *
 * PG callback (PG_CALLBACK) 의 replay 는 *우리* 가 보낸 게 아니라 PG 가 보낸 webhook 의 재처리.
 * 서명 / payload 검증을 다시 거치므로 idempotencyKey 는 무의미 — 응답에 null 로 표기.
 *
 * 본 step 의 범위는 *API 표준 모양*. 실제 PG 호출은 후속 step 에서 `PgClient` 의 charge / refund
 * 를 호출하도록 wiring.
 */
@Component
class PaymentDlqUseCase(
    private val repository: DlqMessageRepository,
) : DlqUseCase {

    private val log = LoggerFactory.getLogger(PaymentDlqUseCase::class.java)
    private val replayCache = ConcurrentHashMap<String, DlqReplayResponse>()

    override fun replay(messageId: String, actor: String, idempotencyKey: String): DlqReplayResponse {
        replayCache[idempotencyKey]?.let { return it }

        val message = repository.find(messageId)
            ?: return DlqReplayResponse(
                messageId = messageId,
                ok = false,
                reason = "NOT_FOUND",
                idempotencyKey = null,
                attemptedAt = Instant.now(),
            )

        // PG 키는 *원 메시지의 헤더* 가 1 차 진실. 없으면 messageId 기반 결정적 키.
        val pgKey = message.headers[IDEMPOTENCY_HEADER]
            ?: message.headers[IDEMPOTENCY_HEADER_LOWER]
            ?: "msg:" + messageId

        val response = when (message.source) {
            DlqSource.PG_CALLBACK ->
                DlqReplayResponse(
                    messageId = messageId,
                    ok = true,
                    reason = null,
                    idempotencyKey = null,            // callback 은 idempotency 적용 대상 아님
                    attemptedAt = Instant.now(),
                )
            DlqSource.PAYMENT_CHARGE, DlqSource.PAYMENT_REFUND, DlqSource.OUTBOX ->
                DlqReplayResponse(
                    messageId = messageId,
                    ok = true,
                    reason = null,
                    idempotencyKey = pgKey,
                    attemptedAt = Instant.now(),
                )
        }
        replayCache[idempotencyKey] = response
        return response
    }

    override fun discard(messageId: String, actor: String, reason: String): DlqDiscardResponse {
        val message = repository.find(messageId)
            ?: return DlqDiscardResponse(messageId, ok = false, reason = reason, discardedAt = Instant.now())
        val ok = repository.delete(messageId)
        if (!ok) {
            log.warn("DLQ discard failed messageId={} source={} actor={}", messageId, message.source, actor)
        }
        return DlqDiscardResponse(messageId, ok, reason, Instant.now())
    }

    companion object {
        const val IDEMPOTENCY_HEADER = "Idempotency-Key"
        const val IDEMPOTENCY_HEADER_LOWER = "idempotency-key"
    }
}
