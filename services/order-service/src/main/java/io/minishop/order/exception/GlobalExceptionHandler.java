package io.minishop.order.exception;

import io.minishop.order.web.dto.OrderResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(OrderNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error("ORDER_NOT_FOUND", e.getMessage()));
    }

    @ExceptionHandler(OrchestrationException.class)
    public ResponseEntity<OrderResponse> handleOrchestration(OrchestrationException e) {
        HttpStatus status = switch (e.getOutcome()) {
            case OUT_OF_STOCK -> HttpStatus.CONFLICT;             // 409
            case PAYMENT_DECLINED -> HttpStatus.PAYMENT_REQUIRED; // 402
            case INVENTORY_INFRA -> HttpStatus.SERVICE_UNAVAILABLE; // 503
            case PAYMENT_INFRA -> HttpStatus.BAD_GATEWAY;          // 502
        };
        return ResponseEntity.status(status)
                .header("X-Order-Outcome", e.getOutcome().name())
                .body(OrderResponse.from(e.getOrder()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return ResponseEntity.badRequest().body(error("VALIDATION_FAILED", detail));
    }

    private Map<String, Object> error(String code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("timestamp", Instant.now().toString());
        m.put("code", code);
        m.put("message", message);
        return m;
    }
}
