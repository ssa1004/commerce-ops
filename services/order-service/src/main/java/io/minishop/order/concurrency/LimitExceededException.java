package io.minishop.order.concurrency;

/**
 * adaptive limiter 의 "지금 동시 처리 가능한 한도를 넘었음" 신호. 호출자는 *즉시 실패* 하고,
 * 외부 응답은 503 + Retry-After 로 매핑된다 (cascade 방지의 핵심 — 줄을 서서 기다리지 않는다).
 */
public class LimitExceededException extends RuntimeException {

    private final String upstream;
    private final int currentLimit;
    private final int retryAfterSeconds;

    public LimitExceededException(String upstream, int currentLimit, int retryAfterSeconds) {
        super("concurrency limit (%d) exceeded for upstream=%s — back-pressure on caller"
                .formatted(currentLimit, upstream));
        this.upstream = upstream;
        this.currentLimit = currentLimit;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public String getUpstream() {
        return upstream;
    }

    public int getCurrentLimit() {
        return currentLimit;
    }

    public int getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
