package io.minishop.order.concurrency

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * client 별 adaptive concurrency limit 설정.
 *
 * 현재 RestClient 가 고정 timeout 만 가지고 있다 — backend 가 느려져도 호출자는 계속 줄을 서서
 * cascade (한 곳의 지연이 호출자 → 호출자 → ... 로 번져가는 도미노 현상) 가 발생한다. adaptive
 * limiter 는 latency 측정으로 동시 진행 중 요청 수를 자동 조절 — backend 가 느려지면 limit 을
 * 자동 축소해 호출자가 더 이상 줄에 끼지 못하게 한다 (LimitExceededException → 503 즉시).
 *
 * 알고리즘은 Netflix concurrency-limits 의 Gradient2 (TCP Vegas 로부터 영감). AWS App Mesh
 * adaptive concurrency 등 service mesh 계열에서도 같은 부류의 알고리즘이 쓰인다. Resilience4j
 * Bulkhead 는 고정 동시 실행 수 — adaptive 와 결정적으로 다름.
 *
 * 정규화 정책 — Java record 의 compact constructor 와 동일하게 모든 invalid 값을 합리 기본으로
 * 보정해 *생성 자체는 실패하지 않게* 한다. yml 의 누락된 필드 (0) 가 운영 default 로 매핑되는
 * 자연스러운 의도. Kotlin data class 는 primary constructor 의 val 을 재대입 못 하므로 (compact
 * constructor 패턴 부재), 일반 class + computed accessors 형태로 동일 시멘틱을 재현. Java caller
 * `new AdaptiveLimiterProperties(true, 0, 0, 0)` 가 그대로 동작.
 */
@ConfigurationProperties(prefix = "mini-shop.concurrency")
class AdaptiveLimiterProperties(
    enabled: Boolean,
    initialLimit: Int,
    minLimit: Int,
    maxLimit: Int,
) {
    @get:JvmName("enabled")
    val enabled: Boolean = enabled

    @get:JvmName("initialLimit")
    val initialLimit: Int

    @get:JvmName("minLimit")
    val minLimit: Int

    @get:JvmName("maxLimit")
    val maxLimit: Int

    init {
        val init0 = if (initialLimit <= 0) 20 else initialLimit
        var min0 = if (minLimit <= 0) 1 else minLimit
        // maxLimit 0 이면 "사실상 무제한" — 100 개로 둠. 더 필요하면 명시 설정.
        var max0 = if (maxLimit <= 0) 100 else maxLimit
        if (min0 > init0) min0 = init0
        if (max0 < init0) max0 = init0
        this.initialLimit = init0
        this.minLimit = min0
        this.maxLimit = max0
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AdaptiveLimiterProperties) return false
        return enabled == other.enabled &&
            initialLimit == other.initialLimit &&
            minLimit == other.minLimit &&
            maxLimit == other.maxLimit
    }

    override fun hashCode(): Int {
        var r = enabled.hashCode()
        r = 31 * r + initialLimit
        r = 31 * r + minLimit
        r = 31 * r + maxLimit
        return r
    }

    override fun toString(): String =
        "AdaptiveLimiterProperties(enabled=$enabled, initialLimit=$initialLimit, minLimit=$minLimit, maxLimit=$maxLimit)"
}
