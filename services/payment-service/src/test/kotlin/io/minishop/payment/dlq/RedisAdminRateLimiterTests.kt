package io.minishop.payment.dlq

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RedisAdminRateLimiterTests {

    @Test
    fun `bulk scope is rate-limited strictly per key`() {
        val l = RedisAdminRateLimiter(readLimit = 60, writeLimit = 30, bulkLimit = 2)
        assertThat(l.tryAcquire("dlq.bulk", "ip-1")).isInstanceOf(AdminRateLimiter.Decision.Allowed::class.java)
        assertThat(l.tryAcquire("dlq.bulk", "ip-1")).isInstanceOf(AdminRateLimiter.Decision.Allowed::class.java)
        val d = l.tryAcquire("dlq.bulk", "ip-1")
        assertThat(d).isInstanceOf(AdminRateLimiter.Decision.Throttled::class.java)
        val t = d as AdminRateLimiter.Decision.Throttled
        assertThat(t.retryAfter.toSeconds()).isBetween(1L, 61L)
    }

    @Test
    fun `read scope and write scope are independent counters`() {
        val l = RedisAdminRateLimiter(readLimit = 2, writeLimit = 2, bulkLimit = 2)
        assertThat(l.tryAcquire("dlq.read", "ip-1")).isInstanceOf(AdminRateLimiter.Decision.Allowed::class.java)
        assertThat(l.tryAcquire("dlq.read", "ip-1")).isInstanceOf(AdminRateLimiter.Decision.Allowed::class.java)
        assertThat(l.tryAcquire("dlq.read", "ip-1")).isInstanceOf(AdminRateLimiter.Decision.Throttled::class.java)
        // write scope 는 아직 0
        assertThat(l.tryAcquire("dlq.write", "ip-1")).isInstanceOf(AdminRateLimiter.Decision.Allowed::class.java)
    }
}
