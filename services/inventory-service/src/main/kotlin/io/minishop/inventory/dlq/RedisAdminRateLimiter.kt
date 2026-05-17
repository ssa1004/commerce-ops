package io.minishop.inventory.dlq

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * inventory-service Admin DLQ rate limiter — order/payment 와 동일한 in-memory fallback.
 *
 * inventory 는 이미 redis (Redisson) 의존성이 있어 운영 단계에서 Redis Lua atomic INCR + EXPIRE
 * 로 자연스럽게 교체 가능. port 만 같으면 service 코드는 변경 없음.
 */
@Component
@ConditionalOnMissingBean(name = ["dlqAdminRateLimiter"])
class RedisAdminRateLimiter(
    @Value("\${mini-shop.dlq.admin.rate.read-per-minute:60}") private val readLimit: Long,
    @Value("\${mini-shop.dlq.admin.rate.write-per-minute:30}") private val writeLimit: Long,
    @Value("\${mini-shop.dlq.admin.rate.bulk-per-minute:5}") private val bulkLimit: Long,
) : AdminRateLimiter {

    private val log = LoggerFactory.getLogger(RedisAdminRateLimiter::class.java)
    private val buckets = ConcurrentHashMap<String, Window>()

    override fun tryAcquire(scope: String, key: String): AdminRateLimiter.Decision {
        val limit = limitFor(scope)
        val full = "$scope|$key"
        val now = Instant.now()
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
