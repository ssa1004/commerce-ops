package io.minishop.order.dlq

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RedisAdminRateLimiterTests {

    @Test
    fun `read scope allows up to limit then throttles`() {
        val l = RedisAdminRateLimiter(readLimit = 3, writeLimit = 5, bulkLimit = 2)
        repeat(3) {
            assertThat(l.tryAcquire("dlq.read", "ip-1"))
                .isInstanceOf(AdminRateLimiter.Decision.Allowed::class.java)
        }
        val decision = l.tryAcquire("dlq.read", "ip-1")
        assertThat(decision).isInstanceOf(AdminRateLimiter.Decision.Throttled::class.java)
        val throttled = decision as AdminRateLimiter.Decision.Throttled
        assertThat(throttled.retryAfter.toSeconds()).isBetween(1L, 61L)
    }

    @Test
    fun `bulk scope is independently rate-limited per key`() {
        val l = RedisAdminRateLimiter(readLimit = 60, writeLimit = 30, bulkLimit = 2)
        assertThat(l.tryAcquire("dlq.bulk", "ip-1")).isInstanceOf(AdminRateLimiter.Decision.Allowed::class.java)
        assertThat(l.tryAcquire("dlq.bulk", "ip-1")).isInstanceOf(AdminRateLimiter.Decision.Allowed::class.java)
        assertThat(l.tryAcquire("dlq.bulk", "ip-1")).isInstanceOf(AdminRateLimiter.Decision.Throttled::class.java)
        // 다른 IP 는 영향 없음
        assertThat(l.tryAcquire("dlq.bulk", "ip-2")).isInstanceOf(AdminRateLimiter.Decision.Allowed::class.java)
    }

    @Test
    fun `unknown scope falls back to read limit`() {
        val l = RedisAdminRateLimiter(readLimit = 1, writeLimit = 99, bulkLimit = 99)
        assertThat(l.tryAcquire("dlq.unknown", "ip-1")).isInstanceOf(AdminRateLimiter.Decision.Allowed::class.java)
        assertThat(l.tryAcquire("dlq.unknown", "ip-1")).isInstanceOf(AdminRateLimiter.Decision.Throttled::class.java)
    }
}
