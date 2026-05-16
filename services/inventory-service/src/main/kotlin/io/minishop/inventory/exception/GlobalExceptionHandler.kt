package io.minishop.inventory.exception

import io.minishop.inventory.service.DistributedLockService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.Instant

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(ProductNotFoundException::class)
    fun handleProductNotFound(e: ProductNotFoundException): ResponseEntity<Map<String, Any>> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(error("PRODUCT_NOT_FOUND", e.message))

    @ExceptionHandler(ReservationNotFoundException::class)
    fun handleReservationNotFound(e: ReservationNotFoundException): ResponseEntity<Map<String, Any>> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(error("RESERVATION_NOT_FOUND", e.message))

    @ExceptionHandler(OutOfStockException::class)
    fun handleOutOfStock(e: OutOfStockException): ResponseEntity<Map<String, Any>> {
        val body = error("OUT_OF_STOCK", e.message)
        body["productId"] = e.productId
        body["requested"] = e.requested
        body["available"] = e.available
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body)
    }

    @ExceptionHandler(DistributedLockService.LockAcquisitionException::class)
    fun handleLockTimeout(e: DistributedLockService.LockAcquisitionException): ResponseEntity<Map<String, Any>> =
        // 분산락 획득 실패는 일시적 — 503으로 클라이언트 재시도 유도
        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(error("LOCK_TIMEOUT", e.message))

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(e: MethodArgumentNotValidException): ResponseEntity<Map<String, Any>> {
        val detail = e.bindingResult.fieldErrors.joinToString(", ") { "${it.field}: ${it.defaultMessage}" }
        return ResponseEntity.badRequest().body(error("VALIDATION_FAILED", detail))
    }

    private fun error(code: String, message: String?): LinkedHashMap<String, Any> {
        val m = LinkedHashMap<String, Any>()
        m["timestamp"] = Instant.now().toString()
        m["code"] = code
        m["message"] = message ?: ""
        return m
    }
}
