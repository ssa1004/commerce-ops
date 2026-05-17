package io.minishop.order.dlq

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant

/**
 * Admin REST API 가 주고받는 6 종 DTO.
 *
 * 설계 원칙:
 * 1. Request 는 *최소 정보만* — 콘솔에서 보내는 payload 의 부주의를 줄인다.
 *    bulk 의 source 는 필수 (한 번에 한 source).
 * 2. Response 는 *결정에 필요한 모든 정보* — 한 화면에서 결정을 내릴 수 있게.
 * 3. dry-run 의 결과는 *실제 실행과 같은 모양* — 운영자가 화면을 두 번 보지 않게.
 *    (notification ADR-0015 의 회고에서 "dry-run 결과가 실행 결과와 모양이 달라 reviewer 가
 *    실수했다" 가 표준화의 출발점이었다.)
 */

/** 단건 / 페이지 목록 응답의 *한 건*. */
data class DlqListItemResponse(
    val messageId: String,
    val source: DlqSource,
    val topic: String,
    val orderId: Long?,
    val customerId: Long?,
    val errorType: String,
    val errorMessageShort: String,
    val firstFailedAt: Instant,
    val lastFailedAt: Instant,
    val attempts: Int,
)

/** 페이지 목록 응답 전체. cursor 기반 — offset 기반은 큰 데이터셋에서 깊은 페이지의 cost 가 폭증. */
data class DlqPageResponse(
    val items: List<DlqListItemResponse>,
    val nextCursor: String?,
    val totalEstimate: Long,
)

/** 단건 상세 — payload / headers / stack 까지 전부. 콘솔 모달에서 한 번에 표시. */
data class DlqDetailResponse(
    val messageId: String,
    val source: DlqSource,
    val topic: String,
    val orderId: Long?,
    val customerId: Long?,
    val errorType: String,
    val errorMessage: String,
    val payload: String,
    val headers: Map<String, String>,
    val firstFailedAt: Instant,
    val lastFailedAt: Instant,
    val attempts: Int,
)

/** replay 의 결과. ok=false 면 [reason] 으로 화면에 사유 표시. */
data class DlqReplayResponse(
    val messageId: String,
    val ok: Boolean,
    val reason: String?,
    val attemptedAt: Instant,
)

/** discard 요청. reason 은 필수 — audit 로그의 핵심 필드. */
data class DlqDiscardRequest(
    @field:NotBlank
    @field:Size(max = 500)
    val reason: String,
)

/** discard 의 결과. */
data class DlqDiscardResponse(
    val messageId: String,
    val ok: Boolean,
    val reason: String,
    val discardedAt: Instant,
)

/** bulk replay/discard 요청.
 *
 * - [source] 필수 — bulk 의 blast radius 를 source 단위로 제한.
 * - [confirm] 가 true 가 아니면 *항상 dry-run* — 실제 실행되지 않고 영향 범위만 리포트.
 * - [errorType] 등 추가 필터는 옵션. cursor 가 깊어지지 않게 from/to 시간 범위는 강하게 권장.
 */
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

/** bulk job 의 응답 — dry-run 도 실행도 같은 모양. */
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

/** 통계 응답 — 버킷 단위 시계열 + 합계 + order-service 특유 차원 (byOrder / byCustomer). */
data class DlqStatsResponse(
    val from: Instant,
    val to: Instant,
    val bucket: String,
    val series: List<DlqStatsBucket>,
    val byErrorType: Map<String, Long>,
    val bySource: Map<DlqSource, Long>,
    val byOrder: Map<Long, Long>,
    val byCustomer: Map<Long, Long>,
    val totalMessages: Long,
)

data class DlqStatsBucket(
    val bucketStart: Instant,
    val count: Long,
)
