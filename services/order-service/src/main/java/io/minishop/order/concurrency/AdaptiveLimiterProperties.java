package io.minishop.order.concurrency;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * client 별 adaptive concurrency limit 설정.
 *
 * <p>현재 RestClient 가 *고정 timeout* 만 가지고 있다 — backend 가 느려져도 호출자는 계속 줄을 서서
 * cascade (한 곳의 지연이 호출자 → 호출자 → ... 로 번져가는 도미노 현상) 가 발생한다. adaptive
 * limiter 는 latency 측정으로 *동시 진행 중 요청 수* 를 자동 조절 — backend 가 느려지면 limit
 * 을 자동 축소해 호출자가 더 이상 줄에 끼지 못하게 한다 (LimitExceededException → 503 즉시).
 *
 * <p>운영 표준 — Netflix concurrency-limits (Gradient2 알고리즘 — TCP Vegas 로부터 영감) 이 AWS,
 * 카카오 / 라인 / 네이버 의 backend mesh 에서 사용 중. Resilience4j 의 Bulkhead 는 *고정* 동시
 * 실행 수 — adaptive 와 결정적으로 다름.
 */
@ConfigurationProperties(prefix = "mini-shop.concurrency")
public record AdaptiveLimiterProperties(
        boolean enabled,
        int initialLimit,
        int minLimit,
        int maxLimit
) {
    public AdaptiveLimiterProperties {
        if (initialLimit <= 0) initialLimit = 20;
        if (minLimit <= 0) minLimit = 1;
        // maxLimit 0 이면 "사실상 무제한" — 100 개로 둠. 더 필요하면 명시 설정.
        if (maxLimit <= 0) maxLimit = 100;
        if (minLimit > initialLimit) minLimit = initialLimit;
        if (maxLimit < initialLimit) maxLimit = initialLimit;
    }
}
