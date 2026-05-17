package io.minishop.inventory.dlq

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * inventory-service 특유 단건 액션 구현 — **Redisson 분산락 재획득** 이 핵심.
 *
 * replay 시:
 * 1. 메시지에서 락 key (`product:{productId}`) 추출.
 * 2. [DlqDistributedLock.withLock] 으로 락 wait + acquire. timeout 이면 즉시 `lockAcquired=false` 응답.
 * 3. 락 안에서 재처리 — 본 step 은 *모양만*. 후속 step 에서 InventoryService 의 reserve/release
 *    를 멱등 호출하도록 wiring (이미 `(orderId, productId)` 키로 멱등 — 두 번 차감 차단).
 * 4. 결과를 `lockAcquired=true` + ok 로 응답.
 *
 * 락 없이 재처리하면 동시 reserve 가 일어나 재고가 음수가 되는 동시성 사고. 이 사고는 [ADR-019]
 * 의 OrderSAGA replay 와는 무관하게 *재고 단독 상태* 만으로 일어날 수 있다 — replay 가 가장
 * 위험한 작업.
 *
 * KAFKA_CONSUME 의 replay 는 락 대상이 아니다 (consumer 측이 자체 멱등 로직 — `existsByXxx`
 * 체크). lockAcquired=false 가 *문제 없음* 의 의미로 응답.
 */
@Component
class InventoryDlqUseCase(
    private val repository: DlqMessageRepository,
    private val lock: DlqDistributedLock,
) : DlqUseCase {

    private val log = LoggerFactory.getLogger(InventoryDlqUseCase::class.java)
    private val replayCache = ConcurrentHashMap<String, DlqReplayResponse>()

    override fun replay(messageId: String, actor: String, idempotencyKey: String): DlqReplayResponse {
        replayCache[idempotencyKey]?.let { return it }

        val message = repository.find(messageId) ?: return DlqReplayResponse(
            messageId = messageId, ok = false, reason = "NOT_FOUND",
            lockAcquired = false, attemptedAt = Instant.now(),
        )

        val response = when (message.source) {
            DlqSource.KAFKA_CONSUME ->
                DlqReplayResponse(
                    messageId = messageId, ok = true, reason = null,
                    lockAcquired = false,                  // consumer 는 락 대상 아님
                    attemptedAt = Instant.now(),
                )
            DlqSource.RESERVE_FAILED, DlqSource.RELEASE_FAILED -> {
                val productId = message.productId
                    ?: return DlqReplayResponse(
                        messageId = messageId, ok = false, reason = "MISSING_PRODUCT_ID",
                        lockAcquired = false, attemptedAt = Instant.now(),
                    )
                val lockKey = "product:$productId"
                when (lock.withLock(lockKey) { Unit }) {
                    is DlqDistributedLock.LockResult.Acquired -> DlqReplayResponse(
                        messageId = messageId, ok = true, reason = null,
                        lockAcquired = true, attemptedAt = Instant.now(),
                    )
                    DlqDistributedLock.LockResult.Timeout -> {
                        log.warn(
                            "DLQ replay lock timeout messageId={} key={} actor={}",
                            messageId, lockKey, actor,
                        )
                        DlqReplayResponse(
                            messageId = messageId, ok = false, reason = "LOCK_TIMEOUT",
                            lockAcquired = false, attemptedAt = Instant.now(),
                        )
                    }
                }
            }
            DlqSource.OUTBOX ->
                DlqReplayResponse(
                    messageId = messageId, ok = true, reason = null,
                    lockAcquired = false,                  // outbox 자체는 락 대상 아님
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
}
