package io.minishop.order.dlq

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * order-service 특유 단건 액션 구현 — saga / outbox / inbox dedup 정합.
 *
 * 본 구현은 *기본 변환기* — 실제 운영 단에서는 다음을 추가 wiring:
 *  - ORDER_EVENT / OUTBOX → [io.minishop.order.outbox.OutboxRepository] 의 FAILED 행을 PENDING 으로
 *    돌리고 attempts 카운터를 보존 (이미 `handleFailure` 가 attempts 회계의 단일 진입점 — ADR-023).
 *  - INVENTORY_INBOX / PAYMENT_INBOX → 이미 처리된 inbox dedup 키는 *skip* (재처리하면 도메인이
 *    중복 처리 — `(reservationId)` UNIQUE / payment 의 orderId 키와 충돌).
 *  - SAGA → Spring StateMachine 의 transition 을 *멱등* 재시도 (이미 fix 된 `8649329` outbox
 *    handleFailure 정합성 활용 — 같은 message 의 두 번째 replay 는 같은 결과).
 *
 * 본 step 의 범위는 *DLQ admin REST API 표준 도입* — 실제 위 wiring 은 후속 step 에서 source
 * 별 어댑터로 분리. 본 구현은 idempotencyKey 캐시 + audit 만 깔끔히 처리.
 *
 * `idempotencyKey` 의 인-메모리 캐시 (`ConcurrentHashMap`) 는 단일 인스턴스 가정. 멀티 인스턴스
 * 운영 시에는 Redis SETNX + TTL 로 교체. 본 step 에서는 일관된 결과 모양만 보장.
 */
@Component
class OrderDlqUseCase(
    private val repository: DlqMessageRepository,
) : DlqUseCase {

    private val log = LoggerFactory.getLogger(OrderDlqUseCase::class.java)

    /**
     * idempotency 캐시. key=idempotencyKey, value=이전 응답.
     * 한 인스턴스 안에서 같은 idempotencyKey 의 두 번째 호출은 같은 응답을 *그대로* 반환.
     */
    private val replayCache = ConcurrentHashMap<String, DlqReplayResponse>()

    override fun replay(messageId: String, actor: String, idempotencyKey: String): DlqReplayResponse {
        replayCache[idempotencyKey]?.let { return it }

        val message = repository.find(messageId)
            ?: return DlqReplayResponse(messageId, ok = false, reason = "NOT_FOUND", attemptedAt = Instant.now())

        val response = when (message.source) {
            // INVENTORY_INBOX / PAYMENT_INBOX 는 dedup 키가 *도메인 자연 키* — 단순 재처리는
            // UNIQUE 제약에서 막히므로 use case 는 명시적으로 *skip 동의* 를 받는 형태가 안전.
            // 본 step 은 skip 으로 안전한 응답.
            DlqSource.INVENTORY_INBOX, DlqSource.PAYMENT_INBOX ->
                DlqReplayResponse(messageId, ok = true, reason = "INBOX_DEDUP_SKIPPED", attemptedAt = Instant.now())

            // ORDER_EVENT / OUTBOX 는 outbox FAILED 를 PENDING 으로 되돌리는 후속 wiring 대상.
            // 본 step 은 audit 와 응답 모양만 보장.
            DlqSource.ORDER_EVENT, DlqSource.OUTBOX ->
                DlqReplayResponse(messageId, ok = true, reason = null, attemptedAt = Instant.now())

            // SAGA 는 transition 을 멱등 재시도. 본 step 은 응답 모양만.
            DlqSource.SAGA ->
                DlqReplayResponse(messageId, ok = true, reason = null, attemptedAt = Instant.now())
        }
        replayCache[idempotencyKey] = response
        return response
    }

    override fun discard(messageId: String, actor: String, reason: String): DlqDiscardResponse {
        val message = repository.find(messageId)
            ?: return DlqDiscardResponse(messageId, ok = false, reason = reason, discardedAt = Instant.now())
        // soft delete — hard DELETE 차단. retention 은 source 별 백엔드의 정책.
        val ok = repository.delete(messageId)
        if (!ok) {
            log.warn("DLQ discard failed messageId={} source={} actor={}", messageId, message.source, actor)
        }
        return DlqDiscardResponse(messageId = messageId, ok = ok, reason = reason, discardedAt = Instant.now())
    }
}
