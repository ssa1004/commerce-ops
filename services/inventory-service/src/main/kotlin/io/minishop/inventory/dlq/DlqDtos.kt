package io.minishop.inventory.dlq

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant

/**
 * inventory-service Admin DLQ DTO 세트. 표준 6 종 + inventory 특유 `byProduct` / `bySku`
 * stats 차원. 자세한 설계 원칙은 ADR-026 참고.
 */

data class DlqListItemResponse(
    val messageId: String,
    val source: DlqSource,
    val topic: String,
    val productId: Long?,
    val sku: String?,
    val orderId: Long?,
    val errorType: String,
    val errorMessageShort: String,
    val firstFailedAt: Instant,
    val lastFailedAt: Instant,
    val attempts: Int,
)

data class DlqPageResponse(
    val items: List<DlqListItemResponse>,
    val nextCursor: String?,
    val totalEstimate: Long,
)

data class DlqDetailResponse(
    val messageId: String,
    val source: DlqSource,
    val topic: String,
    val productId: Long?,
    val sku: String?,
    val orderId: Long?,
    val errorType: String,
    val errorMessage: String,
    val payload: String,
    val headers: Map<String, String>,
    val firstFailedAt: Instant,
    val lastFailedAt: Instant,
    val attempts: Int,
)

data class DlqReplayResponse(
    val messageId: String,
    val ok: Boolean,
    val reason: String?,
    val lockAcquired: Boolean,           // inventory 특유 — 분산락 재획득 결과
    val attemptedAt: Instant,
)

data class DlqDiscardRequest(
    @field:NotBlank
    @field:Size(max = 500)
    val reason: String,
)

data class DlqDiscardResponse(
    val messageId: String,
    val ok: Boolean,
    val reason: String,
    val discardedAt: Instant,
)

data class DlqBulkRequest(
    val source: DlqSource,
    val from: Instant? = null,
    val to: Instant? = null,
    val errorType: String? = null,
    @field:Size(max = 500)
    val reason: String? = null,
    val confirm: Boolean = false,
    @field:Min(1) @field:Max(10_000)
    val maxMessages: Int = 1000,
)

data class DlqBulkJobResponse(
    val jobId: String,
    val source: DlqSource,
    val dryRun: Boolean,
    val status: BulkJobStatus,
    val totalMatched: Long,
    val attempted: Long,
    val succeeded: Long,
    val failed: Long,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val sampleMessageIds: List<String>,
)

enum class BulkJobStatus { QUEUED, RUNNING, SUCCEEDED, PARTIAL, FAILED }

/**
 * inventory 의 stats 차원은 [byProduct] + [bySku]. 재고 사고는 일반적으로 *어떤 상품* 이 영향
 * 받았는가 가 1 차 질문이고, sku 는 그 다음 (color/size 변종 단위 분리).
 */
data class DlqStatsResponse(
    val from: Instant,
    val to: Instant,
    val bucket: String,
    val series: List<DlqStatsBucket>,
    val byErrorType: Map<String, Long>,
    val bySource: Map<DlqSource, Long>,
    val byProduct: Map<Long, Long>,
    val bySku: Map<String, Long>,
    val totalMessages: Long,
)

data class DlqStatsBucket(
    val bucketStart: Instant,
    val count: Long,
)
