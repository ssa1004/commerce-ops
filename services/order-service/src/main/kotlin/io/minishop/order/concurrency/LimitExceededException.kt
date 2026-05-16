package io.minishop.order.concurrency

/**
 * adaptive limiter 의 "지금 동시 처리 가능한 한도를 넘었음" 신호. 호출자는 *즉시 실패* 하고,
 * 외부 응답은 503 + Retry-After 로 매핑된다 (cascade 방지의 핵심 — 줄을 서서 기다리지 않는다).
 */
class LimitExceededException(
    upstream: String,
    currentLimit: Int,
    retryAfterSeconds: Int,
) : RuntimeException(
    "concurrency limit ($currentLimit) exceeded for upstream=$upstream — back-pressure on caller",
) {

    @get:JvmName("getUpstream")
    val upstream: String = upstream

    @get:JvmName("getCurrentLimit")
    val currentLimit: Int = currentLimit

    @get:JvmName("getRetryAfterSeconds")
    val retryAfterSeconds: Int = retryAfterSeconds
}
