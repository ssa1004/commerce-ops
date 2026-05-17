package io.minishop.order.dlq

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Admin DLQ 의 rate limiter.
 *
 * 분당 60 회 기본 (read), bulk 는 분당 5 회.
 * key 는 `admin:dlq:<ip>` — IP 기준. 인증이 들어가면 actor 기준으로 변경.
 *
 * Redis Lua atomic INCR + EXPIRE 가 표준이지만, 본 step 에서는 단일 인스턴스 fallback 으로
 * in-memory 슬라이딩 윈도우만 제공. profile=redis 이거나 Spring data redis bean 이 있으면 후속
 * step 에서 [io.minishop.order.dlq.RedisLuaRateLimiter] 로 교체.
 *
 * (4 service 검증에서 in-memory fallback 이 dev / staging 진입장벽을 낮추는 데 중요했다.
 * 운영은 redis 로 갈아끼우면 됨 — port 만 같으면 service 코드는 변경 없음.)
 */
@Component
@ConditionalOnMissingBean(name = ["dlqAdminRateLimiter"])
class RedisAdminRateLimiter(
    @Value("\${mini-shop.dlq.admin.rate.read-per-minute:60}") private val readLimit: Long,
    @Value("\${mini-shop.dlq.admin.rate.write-per-minute:30}") private val writeLimit: Long,
    @Value("\${mini-shop.dlq.admin.rate.bulk-per-minute:5}") private val bulkLimit: Long,
) : AdminRateLimiter {

    private val log = LoggerFactory.getLogger(RedisAdminRateLimiter::class.java)

    /** key -> 윈도우 (60s) 시작 + 카운터. ConcurrentHashMap + atomic CAS. */
    private val buckets = ConcurrentHashMap<String, Window>()

    override fun tryAcquire(scope: String, key: String): AdminRateLimiter.Decision {
        val limit = limitFor(scope)
        val full = "$scope|$key"
        val now = Instant.now()
        // 윈도우 갱신 또는 새 윈도우 생성.
        val window = buckets.compute(full) { _, existing ->
            if (existing == null || Duration.between(existing.startedAt, now) >= WINDOW) {
                Window(now, AtomicLong(0))
            } else {
                existing
            }
        }!!
        val current = window.count.incrementAndGet()
        return if (current <= limit) {
            AdminRateLimiter.Decision.Allowed(remaining = limit - current)
        } else {
            // 윈도우가 끝나기까지 남은 시간을 retry-after 로.
            val left = WINDOW - Duration.between(window.startedAt, now)
            val retry = if (left.isNegative) Duration.ofSeconds(1) else left
            AdminRateLimiter.Decision.Throttled(retryAfter = retry)
        }
    }

    private fun limitFor(scope: String): Long = when (scope) {
        "dlq.read" -> readLimit
        "dlq.write" -> writeLimit
        "dlq.bulk" -> bulkLimit
        else -> readLimit
    }

    private data class Window(val startedAt: Instant, val count: AtomicLong)

    companion object {
        val WINDOW: Duration = Duration.ofMinutes(1)
    }
}

/**
 * production redis 환경에서는 별도 RedisLuaRateLimiter 를 등록해 [RedisAdminRateLimiter] 를 덮어쓴다.
 * 본 step 에서는 in-memory fallback 만 제공. profile / autoconfig 예시는 다음과 같다 (미사용 시
 * 그대로 두면 in-memory).
 */
@Configuration
@Profile("never")
class RedisLuaRateLimiterConfig
