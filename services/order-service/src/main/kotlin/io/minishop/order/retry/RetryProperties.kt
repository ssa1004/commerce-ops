package io.minishop.order.retry

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 외부 호출 retry 정책. transient 오류 (network blip, 일시 5xx) 는 retry 로 회복하되, 단순 retry 는
 * thundering herd (대규모 동시 retry 가 backend 를 추가로 압박) 를 만들어 backend 회복을 더
 * 어렵게 한다. exponential backoff + jitter 의 결합이 표준 — AWS Architecture Blog (2015)
 * "Exponential Backoff And Jitter" 의 권장.
 *
 * backoff 시간은 다음 식으로 계산:
 * ```
 *   raw     = baseDelayMs * (multiplier ^ (attempt - 1))
 *   capped  = min(raw, maxDelayMs)
 *   wait    = (jitterFactor > 0) ? capped * (1 - jitterFactor + 2 * jitterFactor * rand()) : capped
 * ```
 * (jitterFactor=0.5 → wait 가 capped 의 50%~150% 사이 균일분포. AWS 의 "equal jitter" 와 비슷한
 * 형태.)
 *
 * retry 와 adaptive concurrency limiter 의 직교성: limiter 는 *동시성* 을, retry 는 *시간 분산*
 * 을 조절. 두 메커니즘을 같이 써야 cascade 차단 + transient 회복이 동시에 동작. ADR-022 참조.
 *
 * 정규화 정책 — Java record 의 compact constructor 와 동일하게 모든 invalid 값을 합리 기본으로
 * 보정해 *생성 자체는 실패하지 않게* 한다 (자세한 이유는 AdaptiveLimiterProperties 의 같은 주석).
 */
@ConfigurationProperties(prefix = "mini-shop.retry")
class RetryProperties(
    enabled: Boolean,
    maxAttempts: Int,
    baseDelayMs: Long,
    maxDelayMs: Long,
    multiplier: Double,
    jitterFactor: Double,
    retryOn5xx: Boolean,
) {
    @get:JvmName("enabled")
    val enabled: Boolean = enabled

    @get:JvmName("maxAttempts")
    val maxAttempts: Int

    @get:JvmName("baseDelayMs")
    val baseDelayMs: Long

    @get:JvmName("maxDelayMs")
    val maxDelayMs: Long

    @get:JvmName("multiplier")
    val multiplier: Double

    @get:JvmName("jitterFactor")
    val jitterFactor: Double

    @get:JvmName("retryOn5xx")
    val retryOn5xx: Boolean = retryOn5xx

    init {
        val attempts0 = if (maxAttempts <= 0) 3 else maxAttempts
        var base0 = if (baseDelayMs <= 0) 200L else baseDelayMs
        val max0 = if (maxDelayMs <= 0) 2_000L else maxDelayMs
        val mult0 = if (multiplier < 1.0) 2.0 else multiplier
        // jitterFactor 음수는 의미 없음 — 0 으로 클램프 (jitter 비활성). 1 이상이면 wait 가 음수가
        // 될 수 있음 — 0.99 로 캡.
        val jitter0 = when {
            jitterFactor < 0.0 -> 0.0
            jitterFactor > 0.99 -> 0.99
            else -> jitterFactor
        }
        // baseDelay > maxDelay 는 의미 없는 조합 — base 를 max 로 내림.
        if (base0 > max0) base0 = max0
        this.maxAttempts = attempts0
        this.baseDelayMs = base0
        this.maxDelayMs = max0
        this.multiplier = mult0
        this.jitterFactor = jitter0
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RetryProperties) return false
        return enabled == other.enabled &&
            maxAttempts == other.maxAttempts &&
            baseDelayMs == other.baseDelayMs &&
            maxDelayMs == other.maxDelayMs &&
            multiplier == other.multiplier &&
            jitterFactor == other.jitterFactor &&
            retryOn5xx == other.retryOn5xx
    }

    override fun hashCode(): Int {
        var r = enabled.hashCode()
        r = 31 * r + maxAttempts
        r = 31 * r + baseDelayMs.hashCode()
        r = 31 * r + maxDelayMs.hashCode()
        r = 31 * r + multiplier.hashCode()
        r = 31 * r + jitterFactor.hashCode()
        r = 31 * r + retryOn5xx.hashCode()
        return r
    }

    override fun toString(): String =
        "RetryProperties(enabled=$enabled, maxAttempts=$maxAttempts, baseDelayMs=$baseDelayMs, " +
            "maxDelayMs=$maxDelayMs, multiplier=$multiplier, jitterFactor=$jitterFactor, retryOn5xx=$retryOn5xx)"
}
