package io.minishop.order.exception

import io.minishop.order.web.dto.OrderResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.Instant

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(OrderNotFoundException::class)
    fun handleNotFound(e: OrderNotFoundException): ResponseEntity<Map<String, Any>> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(error("ORDER_NOT_FOUND", e.message ?: ""))

    @ExceptionHandler(OrchestrationException::class)
    fun handleOrchestration(e: OrchestrationException): ResponseEntity<OrderResponse> {
        val status = when (e.outcome) {
            OrchestrationException.Outcome.OUT_OF_STOCK -> HttpStatus.CONFLICT             // 409
            OrchestrationException.Outcome.PAYMENT_DECLINED -> HttpStatus.PAYMENT_REQUIRED // 402
            OrchestrationException.Outcome.INVENTORY_INFRA -> HttpStatus.SERVICE_UNAVAILABLE // 503
            OrchestrationException.Outcome.PAYMENT_INFRA -> HttpStatus.BAD_GATEWAY         // 502
            OrchestrationException.Outcome.UPSTREAM_LIMITED -> HttpStatus.SERVICE_UNAVAILABLE // 503 + Retry-After
        }
        var b: ResponseEntity.BodyBuilder = ResponseEntity.status(status)
            .header("X-Order-Outcome", e.outcome.name)
        if (e.outcome == OrchestrationException.Outcome.UPSTREAM_LIMITED) {
            // Retry-After 는 RFC 7231 — 클라이언트가 *다시 시도해도 되는 시점*. adaptive limiter 가
            // 줄어든 상태에서 즉시 재시도하면 같은 거절을 받으니 1s 가 적절한 기본 (limiter 가
            // backend 회복을 감지하기까지의 평균 윈도우).
            b = b.header(HttpHeaders.RETRY_AFTER, "1")
        }
        return b.body(OrderResponse.from(e.order))
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(e: MethodArgumentNotValidException): ResponseEntity<Map<String, Any>> {
        val detail = e.bindingResult.fieldErrors.joinToString(", ") { "${it.field}: ${it.defaultMessage}" }
        return ResponseEntity.badRequest().body(error("VALIDATION_FAILED", detail))
    }

    private fun error(code: String, message: String): Map<String, Any> = linkedMapOf(
        "timestamp" to Instant.now().toString(),
        "code" to code,
        "message" to message,
    )
}
