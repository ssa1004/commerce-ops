package io.minishop.order.dlq

import java.time.Duration
import java.time.Instant

/**
 * DLQ admin 의 4 종 port (hexagonal — 도메인이 외부 기술에 종속되지 않게 인터페이스로 묶음).
 *
 * Service 는 port 만 보고, 실제 구현 (Redis / Kafka admin / DB / Slf4j) 은 adapter (`*Adapter` /
 * `RedisAdminRateLimiter` / `InMemoryDlqBulkJobRepository` / `Slf4jDlqAuditLog`) 가 제공.
 * 테스트는 port 를 fake 로 대체해 트랜잭션/Redis 없이 검증.
 */

/** 1. UseCase — 단건 replay / discard 의 비즈니스 결정.
 *
 *  service 마다 서로 다른 *후처리* (order: saga + outbox 정합 / inbox dedup) 가 들어가므로
 *  단건 액션은 *별도 port* 로 분리해 service 의 RestController 가 알 필요가 없게 둔다.
 */
interface DlqUseCase {
    /** 단건 replay. idempotencyKey 가 같으면 같은 결과를 반환 (정확히 한 번 액션). */
    fun replay(messageId: String, actor: String, idempotencyKey: String): DlqReplayResponse

    /** 단건 discard. reason 은 필수, audit 로그의 핵심 필드. */
    fun discard(messageId: String, actor: String, reason: String): DlqDiscardResponse
}

/** 2. RateLimiter — admin 요청의 분당 호출 한도.
 *
 *  scope (`dlq.read` / `dlq.write` / `dlq.bulk`) 로 강도를 분리. bulk 는 분당 5 회 정도로 강하게.
 *  read 는 분당 60 회. write 는 분당 30 회.
 *
 *  Redis Lua atomic INCR + EXPIRE 로 구현 — 인스턴스 여러 개에서도 같은 카운터.
 *  test 에서는 InMemory 구현으로 대체.
 */
interface AdminRateLimiter {
    /** 차단되지 않았으면 [Decision.Allowed], 차단되었으면 retry-after 와 함께 [Decision.Throttled]. */
    fun tryAcquire(scope: String, key: String): Decision

    sealed interface Decision {
        data class Allowed(val remaining: Long) : Decision
        data class Throttled(val retryAfter: Duration) : Decision
    }
}

/** 3. Repository — DLQ 메시지의 저장/조회 + bulk job 의 진행 상태.
 *
 *  source 마다 실제 백엔드는 다르다 (Kafka admin / outbox FAILED rows / saga error log).
 *  port 는 그 차이를 흡수해 *Service 입장에선 한 가지 모양*.
 *
 *  bulk job 은 별도 메서드 — long-running 폴링용. notification / billing 에서 검증된 형태.
 */
interface DlqMessageRepository {
    fun list(query: DlqListQuery): DlqPageResponse
    fun find(messageId: String): DlqMessage?
    fun delete(messageId: String): Boolean

    /** 통계 — 시간 범위 + 버킷 사이즈 + (orderId / customerId / errorType / source) 차원. */
    fun stats(from: Instant, to: Instant, bucket: Duration): DlqStatsResponse

    /** bulk 의 *match* — 실제 액션 전에 매칭 결과만 산정. dry-run 결과의 source. */
    fun match(filter: DlqBulkFilter): List<String>
}

/** list 조회의 query 객체 — 너무 많은 파라미터를 한 메서드 시그니처에 직접 두지 않게. */
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

/** Bulk job 진행 상태의 저장소. in-memory (단일 인스턴스 단순) 또는 Redis (멀티 인스턴스). */
interface DlqBulkJobRepository {
    fun create(initial: DlqBulkJobResponse): DlqBulkJobResponse
    fun update(jobId: String, transform: (DlqBulkJobResponse) -> DlqBulkJobResponse): DlqBulkJobResponse?
    fun find(jobId: String): DlqBulkJobResponse?
}

/** 4. AuditLog — `DLQ_REPLAY` / `DLQ_DISCARD` / `DLQ_BULK_*` audit 출력.
 *
 *  - actor — 누가 (헤더 `X-Actor` 또는 인증 컨텍스트에서)
 *  - target — 무엇을 (messageId / source / orderId / customerId)
 *  - reason — 왜 (사용자 입력 또는 시스템 자동 — bulk 의 경우 dry-run 결과 요약)
 *  - result — 어떻게 (ok=true/false, attempts, 영향 행 수)
 *
 *  운영 회고 시 grep 으로 추적 가능. Loki 로 흘러간 다음에는 trace_id 로 묶이도록 MDC 도 같이.
 */
interface DlqAuditLog {
    fun log(event: AuditEvent)

    data class AuditEvent(
        val action: String,
        val actor: String,
        val source: DlqSource?,
        val messageId: String?,
        val orderId: Long? = null,
        val customerId: Long? = null,
        val reason: String? = null,
        val result: String,
        val extra: Map<String, String> = emptyMap(),
    )
}
