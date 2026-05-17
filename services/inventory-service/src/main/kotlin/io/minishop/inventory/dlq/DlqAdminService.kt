package io.minishop.inventory.dlq

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * inventory-service DLQ admin 의 *얇은* 오케스트레이션. ADR-026 의 표준 service 1 종.
 *
 * inventory 특유:
 * - replay 의 결과에 `lockAcquired` 가 포함 — 분산락 timeout 인 경우 콘솔이 즉시 인지.
 * - bulk-replay 의 *executor* 는 use case 의 replay 결과를 그대로 사용 — 락 timeout 인 한 건이
 *   다음 건의 진행을 막지 않는다 (다른 productId 의 락은 독립).
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

    fun replay(messageId: String, actor: String, idempotencyKey: String): DlqReplayResponse {
        val message = repository.find(messageId)
        val response = useCase.replay(messageId, actor, idempotencyKey)
        audit.log(
            DlqAuditLog.AuditEvent(
                action = "DLQ_REPLAY",
                actor = actor,
                source = message?.source,
                messageId = messageId,
                productId = message?.productId,
                sku = message?.sku,
                orderId = message?.orderId,
                reason = "single replay",
                result = if (response.ok) "OK" else "FAILED:${response.reason}",
                extra = mapOf(
                    "idempotencyKey" to idempotencyKey,
                    "lockAcquired" to response.lockAcquired.toString(),
                ),
            )
        )
        return response
    }

    fun discard(messageId: String, actor: String, reason: String): DlqDiscardResponse {
        val message = repository.find(messageId)
        val response = useCase.discard(messageId, actor, reason)
        audit.log(
            DlqAuditLog.AuditEvent(
                action = "DLQ_DISCARD",
                actor = actor,
                source = message?.source,
                messageId = messageId,
                productId = message?.productId,
                sku = message?.sku,
                orderId = message?.orderId,
                reason = reason,
                result = if (response.ok) "OK" else "FAILED",
            )
        )
        return response
    }

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
        if (request.from != null && request.to != null) {
            require(!request.to.isBefore(request.from)) { "bulk: 'to' must not be before 'from'" }
        }
    }

    private fun DlqBulkRequest.toFilter(): DlqBulkFilter =
        DlqBulkFilter(source, from, to, errorType, maxMessages)
}

@Service
class DlqBulkJobService(private val repository: DlqBulkJobRepository) {

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
        const val SAMPLE_LIMIT = 20
    }
}
