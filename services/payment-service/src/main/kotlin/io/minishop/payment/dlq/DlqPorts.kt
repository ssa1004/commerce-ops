package io.minishop.payment.dlq

import java.time.Duration
import java.time.Instant

/**
 * payment-service DLQ admin 의 4 종 port. ADR-026 의 표준 모양.
 */

interface DlqUseCase {
    /**
     * 단건 replay. payment-service 특유 — 원 메시지의 `Idempotency-Key` 헤더를 그대로 복사해
     * PG 가 같은 결제로 인식하도록 보장. 응답의 `idempotencyKey` 필드에 사용한 키를 노출
     * (콘솔에서 PG 의 audit 와 매칭하기 위함 — billing 의 회고).
     */
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
        val paymentId: Long? = null,
        val customerId: Long? = null,
        val reason: String? = null,
        val result: String,
        val extra: Map<String, String> = emptyMap(),
    )
}
