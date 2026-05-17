package io.minishop.order.web

import io.minishop.order.dlq.AdminRateLimiter
import io.minishop.order.dlq.DlqAdminService
import io.minishop.order.dlq.DlqBulkRequest
import io.minishop.order.dlq.DlqDetailResponse
import io.minishop.order.dlq.DlqDiscardRequest
import io.minishop.order.dlq.DlqListQuery
import io.minishop.order.dlq.DlqSource
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import java.time.Instant

/**
 * Admin DLQ REST 컨트롤러.
 *
 * 표준 (notification ADR-0015 / billing ADR-0033 / market ADR-0028 / gpu ADR-0026) 의 8 endpoint
 * 를 *그대로* 노출.
 *
 * 인증 모델 — 현재 commerce-ops 는 별도 admin auth 가 없어 헤더 `X-Admin-Role` 만 검사.
 * 운영 단계에서 [Spring Security 의 @PreAuthorize] 로 교체. 헤더 우회는 ingress 단의 ACL 로 막는다
 * (예: K8s NetworkPolicy + nginx 의 path-allowlist).
 *
 * actor 는 헤더 `X-Actor` — 누가 호출했는지 audit 로그의 핵심 필드.
 */
@RestController
@RequestMapping("/api/v1/admin/dlq")
@Validated
class AdminDlqController(
    private val service: DlqAdminService,
    private val rateLimiter: AdminRateLimiter,
) {

    @GetMapping
    fun list(
        @RequestParam(required = false) source: DlqSource?,
        @RequestParam(required = false) topic: String?,
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
        @RequestParam(required = false) errorType: String?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false, defaultValue = "20") @Min(1) @Max(100) size: Int,
        request: HttpServletRequest,
    ): ResponseEntity<*> {
        ensure(request, scope = "dlq.read")?.let { return it }
        val page = service.list(DlqListQuery(source, topic, from, to, errorType, cursor, size))
        return ResponseEntity.ok(page)
    }

    @GetMapping("/{messageId}")
    fun get(
        @PathVariable messageId: String,
        request: HttpServletRequest,
    ): ResponseEntity<*> {
        ensure(request, scope = "dlq.read")?.let { return it }
        val m = service.get(messageId) ?: return ResponseEntity.notFound().build<Void>()
        return ResponseEntity.ok(
            DlqDetailResponse(
                messageId = m.messageId,
                source = m.source,
                topic = m.topic,
                orderId = m.orderId,
                customerId = m.customerId,
                errorType = m.errorType,
                errorMessage = m.errorMessage,
                payload = m.payload,
                headers = m.headers,
                firstFailedAt = m.firstFailedAt,
                lastFailedAt = m.lastFailedAt,
                attempts = m.attempts,
            )
        )
    }

    @PostMapping("/{messageId}/replay")
    fun replay(
        @PathVariable messageId: String,
        @RequestHeader(name = "X-Idempotency-Key", required = false) idempotencyKey: String?,
        @RequestHeader(name = "X-Actor", required = false) actor: String?,
        request: HttpServletRequest,
    ): ResponseEntity<*> {
        ensure(request, scope = "dlq.write")?.let { return it }
        val key = idempotencyKey ?: "auto:" + java.util.UUID.randomUUID()
        val a = actor ?: "anonymous"
        val response = service.replay(messageId, a, key)
        val status = if (response.ok) HttpStatus.OK else HttpStatus.UNPROCESSABLE_ENTITY
        return ResponseEntity.status(status).body(response)
    }

    @PostMapping("/{messageId}/discard")
    fun discard(
        @PathVariable messageId: String,
        @Valid @RequestBody body: DlqDiscardRequest,
        @RequestHeader(name = "X-Actor", required = false) actor: String?,
        request: HttpServletRequest,
    ): ResponseEntity<*> {
        ensure(request, scope = "dlq.write")?.let { return it }
        val a = actor ?: "anonymous"
        val response = service.discard(messageId, a, body.reason)
        val status = if (response.ok) HttpStatus.OK else HttpStatus.UNPROCESSABLE_ENTITY
        return ResponseEntity.status(status).body(response)
    }

    @PostMapping("/bulk-replay")
    fun bulkReplay(
        @Valid @RequestBody body: DlqBulkRequest,
        @RequestHeader(name = "X-Actor", required = false) actor: String?,
        request: HttpServletRequest,
    ): ResponseEntity<*> {
        ensure(request, scope = "dlq.bulk")?.let { return it }
        val response = service.bulkReplay(body, actor ?: "anonymous")
        return ResponseEntity.accepted().body(response)
    }

    @PostMapping("/bulk-discard")
    fun bulkDiscard(
        @Valid @RequestBody body: DlqBulkRequest,
        @RequestHeader(name = "X-Actor", required = false) actor: String?,
        request: HttpServletRequest,
    ): ResponseEntity<*> {
        ensure(request, scope = "dlq.bulk")?.let { return it }
        val response = service.bulkDiscard(body, actor ?: "anonymous")
        return ResponseEntity.accepted().body(response)
    }

    @GetMapping("/bulk-jobs/{jobId}")
    fun bulkJob(
        @PathVariable jobId: String,
        request: HttpServletRequest,
    ): ResponseEntity<*> {
        ensure(request, scope = "dlq.read")?.let { return it }
        val job = service.bulkJob(jobId) ?: return ResponseEntity.notFound().build<Void>()
        return ResponseEntity.ok(job)
    }

    @GetMapping("/stats")
    fun stats(
        @RequestParam from: Instant,
        @RequestParam to: Instant,
        @RequestParam(required = false, defaultValue = "PT1H") bucket: Duration,
        request: HttpServletRequest,
    ): ResponseEntity<*> {
        ensure(request, scope = "dlq.read")?.let { return it }
        return ResponseEntity.ok(service.stats(from, to, bucket))
    }

    /**
     * 인증 + rate limit. 차단되면 즉시 응답을 반환 (caller 는 null 이면 통과).
     */
    private fun ensure(request: HttpServletRequest, scope: String): ResponseEntity<Void>? {
        val role = request.getHeader("X-Admin-Role")
        if (role.isNullOrBlank() || !ALLOWED_ROLES.contains(role)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }
        val key = "admin:dlq:" + clientIp(request)
        return when (val decision = rateLimiter.tryAcquire(scope, key)) {
            is AdminRateLimiter.Decision.Allowed -> null
            is AdminRateLimiter.Decision.Throttled ->
                ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, decision.retryAfter.toSeconds().coerceAtLeast(1).toString())
                    .build()
        }
    }

    private fun clientIp(request: HttpServletRequest): String {
        val fwd = request.getHeader("X-Forwarded-For")
        if (!fwd.isNullOrBlank()) {
            return fwd.substringBefore(',').trim()
        }
        return request.remoteAddr ?: "unknown"
    }

    companion object {
        private val ALLOWED_ROLES = setOf("DLQ_ADMIN", "PLATFORM_ADMIN")
    }
}
