package io.minishop.order.retry;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 외부 호출 retry 정책. transient 오류 (network blip, 일시 5xx) 는 retry 로 회복하되, 단순 retry 는
 * thundering herd (대규모 동시 retry 가 backend 를 추가로 압박) 를 만들어 backend 회복을 더
 * 어렵게 한다. exponential backoff + jitter 의 결합이 표준 — AWS Architecture Blog (2015)
 * "Exponential Backoff And Jitter" 의 권장.
 *
 * <p>backoff 시간은 다음 식으로 계산:
 * <pre>
 *   raw     = baseDelayMs * (multiplier ^ (attempt - 1))
 *   capped  = min(raw, maxDelayMs)
 *   wait    = (jitterFactor &gt; 0) ? capped * (1 - jitterFactor + 2 * jitterFactor * rand()) : capped
 * </pre>
 * (jitterFactor=0.5 → wait 가 capped 의 50%~150% 사이 균일분포. AWS 의 "equal jitter" 와 비슷한
 * 형태.)
 *
 * <p>retry 와 adaptive concurrency limiter 의 직교성: limiter 는 *동시성* 을, retry 는 *시간 분산*
 * 을 조절. 두 메커니즘을 같이 써야 cascade 차단 + transient 회복이 동시에 동작. ADR-022 참조.
 */
@ConfigurationProperties(prefix = "mini-shop.retry")
public record RetryProperties(
        boolean enabled,
        int maxAttempts,
        long baseDelayMs,
        long maxDelayMs,
        double multiplier,
        double jitterFactor,
        boolean retryOn5xx
) {
    public RetryProperties {
        if (maxAttempts <= 0) maxAttempts = 3;
        if (baseDelayMs <= 0) baseDelayMs = 200L;
        if (maxDelayMs <= 0) maxDelayMs = 2000L;
        if (multiplier < 1.0) multiplier = 2.0;
        // jitterFactor 음수는 의미 없음 — 0 으로 클램프 (jitter 비활성).
        if (jitterFactor < 0.0) jitterFactor = 0.0;
        // 1 이상이면 wait 가 음수가 될 수 있음 — 0.99 로 캡.
        if (jitterFactor > 0.99) jitterFactor = 0.99;
        // baseDelay > maxDelay 는 의미 없는 조합 — base 를 max 로 내림.
        if (baseDelayMs > maxDelayMs) baseDelayMs = maxDelayMs;
    }
}
