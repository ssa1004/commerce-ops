package io.minishop.correlation;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * correlation-mdc-starter 의 ConfigurationProperties.
 *
 * <p>설계 메모 — Phase 1 의 *최소* 구현 범위.
 * <ul>
 *   <li>현재는 trace_id / span_id 만 MDC 에 동기화. 비즈니스 attribute (X-User-Id 헤더 → MDC 등)
 *       은 ADR-013 의 PII 마스킹 정책과 함께 후속 단계에서 도입.</li>
 *   <li>WebFlux / Kafka consumer / Reactor Context 도 후속 단계 — 본 starter 의 첫 동작 단위는
 *       Spring MVC (Servlet) 한정.</li>
 * </ul>
 *
 * @param enabled 모듈 활성 여부. 기본 true.
 * @param traceIdKey MDC 에 trace_id 를 쓸 키 이름. 기본 "trace_id" — Logback 패턴에서 {@code %X{trace_id}}.
 * @param spanIdKey MDC 에 span_id 를 쓸 키 이름. 기본 "span_id".
 */
@ConfigurationProperties(prefix = "mini-shop.correlation")
public record CorrelationMdcProperties(
        boolean enabled,
        String traceIdKey,
        String spanIdKey
) {
    public CorrelationMdcProperties {
        if (traceIdKey == null || traceIdKey.isBlank()) traceIdKey = "trace_id";
        if (spanIdKey == null || spanIdKey.isBlank()) spanIdKey = "span_id";
    }
}
