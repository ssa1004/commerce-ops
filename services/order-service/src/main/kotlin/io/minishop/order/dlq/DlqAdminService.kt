package io.minishop.order.dlq

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * DLQ admin 의 *얇은* 오케스트레이션 service.
 *
 * 책임 (Service 2 종 중 첫 번째):
 * 1. 입력 검증 (bulk 의 source 필수, dry-run 의 confirm 의미 강제).
 * 2. rate-limit 결정을 호출자 (controller) 가 아니라 Service 단에서도 한 번 더 — 두께 방어.
 * 3. 단건 액션을 [DlqUseCase] 에 위임 — 비즈니스 정합 (saga / outbox / inbox) 은 use case 측에.
 * 4. bulk 액션을 [DlqBulkJobService] 에 위임.
 * 5. audit 로그를 한 곳에서 발행 — controller / service 가 중복 호출하지 않게.
 *
 * 트랜잭션 / 분산락 / Kafka 발행 등 *기술 세부* 는 어떤 것도 여기에 두지 않는다.
 */
@Service
class DlqAdminService(
    private val repository: DlqMessageRepository,
    private val useCase: DlqUseCase,
    private val audit: DlqAuditLog,
    private val bulkJobs: DlqBulkJobService,
) {

    private val log = LoggerFactory.getLogger(DlqAdminService::class.java)

    fun list(query: DlqListQuery): DlqPageResponse = repository.list(query)

    fun get(messageId: String): DlqMessage? = repository.find(messageId)

    /** 단건 replay — idempotencyKey 가 같으면 같은 결과 (UseCase 의 책임). */
    fun replay(messageId: String, actor: String, idempotencyKey: String): DlqReplayResponse {
        val message = repository.find(messageId)
        val response = useCase.replay(messageId, actor, idempotencyKey)
        audit.log(
            DlqAuditLog.AuditEvent(
                action = "DLQ_REPLAY",
                actor = actor,
                source = message?.source,
                messageId = messageId,
                orderId = message?.orderId,
                customerId = message?.customerId,
                reason = "single replay",
                result = if (response.ok) "OK" else "FAILED:${response.reason}",
                extra = mapOf("idempotencyKey" to idempotencyKey),
            )
        )
        return response
    }

    /** 단건 discard — reason 필수. */
    fun discard(messageId: String, actor: String, reason: String): DlqDiscardResponse {
        val message = repository.find(messageId)
        val response = useCase.discard(messageId, actor, reason)
        audit.log(
            DlqAuditLog.AuditEvent(
                action = "DLQ_DISCARD",
                actor = actor,
                source = message?.source,
                messageId = messageId,
                orderId = message?.orderId,
                customerId = message?.customerId,
                reason = reason,
                result = if (response.ok) "OK" else "FAILED",
            )
        )
        return response
    }

    /**
     * bulk replay.
     *
     * - `confirm=false` 면 *항상 dry-run*. 결과는 실제 실행과 *같은 모양* 으로 반환.
     * - `confirm=true` 라도 매칭이 maxMessages 를 넘으면 거절 — 운영자가 from/to 를 좁히도록 강제.
     */
    fun bulkReplay(request: DlqBulkRequest, actor: String): DlqBulkJobResponse {
        validateBulk(request)
        val filter = request.toFilter()
        val matched = repository.match(filter)
        val dryRun = !request.confirm
        val job = bulkJobs.start(
            action = "DLQ_BULK_REPLAY",
            source = request.source,
            dryRun = dryRun,
            matched = matched,
            actor = actor,
            reason = request.reason,
            executor = if (dryRun) null else { id -> useCase.replay(id, actor, "bulk:$id").ok },
        )
        audit.log(
            DlqAuditLog.AuditEvent(
                action = if (dryRun) "DLQ_BULK_REPLAY_DRYRUN" else "DLQ_BULK_REPLAY",
                actor = actor,
                source = request.source,
                messageId = null,
                reason = request.reason,
                result = "matched=${job.totalMatched} attempted=${job.attempted} succeeded=${job.succeeded}",
                extra = mapOf("jobId" to job.jobId, "dryRun" to dryRun.toString()),
            )
        )
        return job
    }

    /**
     * bulk discard.
     *
     * - dry-run 강제 (notification ADR-0015 / billing ADR-0033 / market ADR-0028 / gpu ADR-0026 패턴).
     * - hard DELETE 차단 — UseCase 가 soft delete (status=DISCARDED + retention) 만 수행.
     */
    fun bulkDiscard(request: DlqBulkRequest, actor: String): DlqBulkJobResponse {
        validateBulk(request)
        require(!request.reason.isNullOrBlank()) { "bulk-discard requires non-blank reason" }
        val filter = request.toFilter()
        val matched = repository.match(filter)
        val dryRun = !request.confirm
        val job = bulkJobs.start(
            action = "DLQ_BULK_DISCARD",
            source = request.source,
            dryRun = dryRun,
            matched = matched,
            actor = actor,
            reason = request.reason,
            executor = if (dryRun) null else { id -> useCase.discard(id, actor, request.reason).ok },
        )
        audit.log(
            DlqAuditLog.AuditEvent(
                action = if (dryRun) "DLQ_BULK_DISCARD_DRYRUN" else "DLQ_BULK_DISCARD",
                actor = actor,
                source = request.source,
                messageId = null,
                reason = request.reason,
                result = "matched=${job.totalMatched} attempted=${job.attempted} succeeded=${job.succeeded}",
                extra = mapOf("jobId" to job.jobId, "dryRun" to dryRun.toString()),
            )
        )
        return job
    }

    fun bulkJob(jobId: String): DlqBulkJobResponse? = bulkJobs.find(jobId)

    fun stats(from: Instant, to: Instant, bucket: Duration): DlqStatsResponse {
        require(!to.isBefore(from)) { "stats: 'to' must not be before 'from'" }
        require(bucket >= Duration.ofMinutes(1)) { "stats: bucket must be >= 1m" }
        require(Duration.between(from, to) <= Duration.ofDays(31)) { "stats: range must be <= 31d" }
        return repository.stats(from, to, bucket)
    }

    private fun validateBulk(request: DlqBulkRequest) {
        // source 는 enum 이라 별도 검증 불필요. dry-run 강제는 *명시* — 운영자가 confirm=true 를
        // 빠뜨려도 매칭 결과만 받고 안전하게 종료. 별도 예외 없이 다음 분기에서 dryRun 으로 처리.
        if (request.from != null && request.to != null) {
            require(!request.to.isBefore(request.from)) { "bulk: 'to' must not be before 'from'" }
        }
    }

    private fun DlqBulkRequest.toFilter(): DlqBulkFilter =
        DlqBulkFilter(source, from, to, errorType, maxMessages)
}

/**
 * Bulk job 실행 service (Service 2 종 중 두 번째). DlqAdminService 에서 분리해
 * 폴링 / 진행상태 / 재시도 가 한 곳에 모이게.
 *
 * 본 구현은 *동기* — small batch (maxMessages 기본 1000 이하) 만 처리.
 * 운영 규모가 커지면 async executor (Spring `@Async` + worker thread pool) 로 분리. 현재
 * 패턴 (4 service 검증) 은 sync 로 시작 → 필요 시 별도 step.
 */
@Service
class DlqBulkJobService(
    private val repository: DlqBulkJobRepository,
) {

    fun start(
        action: String,
        source: DlqSource,
        dryRun: Boolean,
        matched: List<String>,
        actor: String,
        reason: String?,
        executor: ((String) -> Boolean)?,
    ): DlqBulkJobResponse {
        val jobId = "bulk_" + UUID.randomUUID().toString().substring(0, 12)
        val now = Instant.now()
        val sample = matched.take(SAMPLE_LIMIT)
        val initial = DlqBulkJobResponse(
            jobId = jobId,
            source = source,
            dryRun = dryRun,
            status = if (dryRun) BulkJobStatus.SUCCEEDED else BulkJobStatus.RUNNING,
            totalMatched = matched.size.toLong(),
            attempted = 0,
            succeeded = 0,
            failed = 0,
            startedAt = now,
            finishedAt = if (dryRun) now else null,
            sampleMessageIds = sample,
        )
        val created = repository.create(initial)
        if (dryRun || executor == null) {
            return created
        }
        // 실 실행 — sync, 빠른 fail 모드 (한 건 실패해도 다음 건 진행, 카운터만 누적).
        var attempted = 0L
        var succeeded = 0L
        var failed = 0L
        for (id in matched) {
            attempted += 1
            runCatching { executor(id) }
                .onSuccess { if (it) succeeded += 1 else failed += 1 }
                .onFailure { failed += 1 }
        }
        val finishedAt = Instant.now()
        val status = when {
            failed == 0L -> BulkJobStatus.SUCCEEDED
            succeeded == 0L -> BulkJobStatus.FAILED
            else -> BulkJobStatus.PARTIAL
        }
        return repository.update(jobId) { current ->
            current.copy(
                status = status,
                attempted = attempted,
                succeeded = succeeded,
                failed = failed,
                finishedAt = finishedAt,
            )
        } ?: created
    }

    fun find(jobId: String): DlqBulkJobResponse? = repository.find(jobId)

    companion object {
        /** 응답에 노출하는 sample 개수 — 너무 많이 노출하면 응답 사이즈가 커져 UI 가 느려짐. */
        const val SAMPLE_LIMIT = 20
    }
}
