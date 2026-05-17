package io.minishop.inventory.dlq

import java.time.Duration
import java.time.Instant

/**
 * inventory-service DLQ admin 의 4 종 port. ADR-026 의 표준 모양.
 *
 * inventory 특유 — [DlqUseCase.replay] 의 응답에 `lockAcquired` 필드가 있다.
 * 분산락 재획득 결과를 콘솔이 그대로 표시 — 락 timeout 으로 실패한 replay 의 원인이 한눈에 보임.
 */

interface DlqUseCase {
    fun replay(messageId: String, actor: String, idempotencyKey: String): DlqReplayResponse
    fun discard(messageId: String, actor: String, reason: String): DlqDiscardResponse
}

interface AdminRateLimiter {
    fun tryAcquire(scope: String, key: String): Decision

    sealed interface Decision {
        data class Allowed(val remaining: Long) : Decision
        data class Throttled(val retryAfter: Duration) : Decision
    }
}

interface DlqMessageRepository {
    fun list(query: DlqListQuery): DlqPageResponse
    fun find(messageId: String): DlqMessage?
    fun delete(messageId: String): Boolean
    fun stats(from: Instant, to: Instant, bucket: Duration): DlqStatsResponse
    fun match(filter: DlqBulkFilter): List<String>
}

data class DlqListQuery(
    val source: DlqSource? = null,
    val topic: String? = null,
    val from: Instant? = null,
    val to: Instant? = null,
    val errorType: String? = null,
    val cursor: String? = null,
    val size: Int = 20,
)

data class DlqBulkFilter(
    val source: DlqSource,
    val from: Instant? = null,
    val to: Instant? = null,
    val errorType: String? = null,
    val maxMessages: Int = 1000,
)

interface DlqBulkJobRepository {
    fun create(initial: DlqBulkJobResponse): DlqBulkJobResponse
    fun update(jobId: String, transform: (DlqBulkJobResponse) -> DlqBulkJobResponse): DlqBulkJobResponse?
    fun find(jobId: String): DlqBulkJobResponse?
}

interface DlqAuditLog {
    fun log(event: AuditEvent)

    data class AuditEvent(
        val action: String,
        val actor: String,
        val source: DlqSource?,
        val messageId: String?,
        val productId: Long? = null,
        val sku: String? = null,
        val orderId: Long? = null,
        val reason: String? = null,
        val result: String,
        val extra: Map<String, String> = emptyMap(),
    )
}

/**
 * inventory 특유 — DLQ replay 가 *재처리할 자원* 의 분산락 (`product:{productId}`) 을 재획득.
 * use case 는 본 port 를 호출해 락 안에서 reserve / release 를 멱등하게 재실행.
 *
 * 본 step 은 *port 만 정의* — 실제 wiring 은 후속 step (DistributedLockService 를 어댑터로 감쌈).
 * test 에서는 in-memory fake 로 대체.
 */
interface DlqDistributedLock {
    /**
     * 주어진 key (예: `product:{productId}`) 의 락을 wait 까지 기다려 얻으면 true,
     * timeout 이거나 인터럽트 되면 false. 본 인터페이스는 *결과만* 노출 — 실제 lease 관리는
     * 어댑터 (Redisson 의 leaseTime / waitTime) 가 책임.
     */
    fun <T> withLock(key: String, action: () -> T): LockResult<T>

    sealed interface LockResult<out T> {
        data class Acquired<T>(val value: T) : LockResult<T>
        data object Timeout : LockResult<Nothing>
    }
}
