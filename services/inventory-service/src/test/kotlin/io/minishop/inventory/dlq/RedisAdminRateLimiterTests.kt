package io.minishop.inventory.dlq

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RedisAdminRateLimiterTests {

    @Test
    fun `bulk scope is strictly limited per key`() {
        val l = RedisAdminRateLimiter(readLimit = 60, writeLimit = 30, bulkLimit = 2)
        assertThat(l.tryAcquire("dlq.bulk", "ip-1")).isInstanceOf(AdminRateLimiter.Decision.Allowed::class.java)
        assertThat(l.tryAcquire("dlq.bulk", "ip-1")).isInstanceOf(AdminRateLimiter.Decision.Allowed::class.java)
        assertThat(l.tryAcquire("dlq.bulk", "ip-1")).isInstanceOf(AdminRateLimiter.Decision.Throttled::class.java)
        // 다른 IP 의 카운터는 독립
        assertThat(l.tryAcquire("dlq.bulk", "ip-2")).isInstanceOf(AdminRateLimiter.Decision.Allowed::class.java)
    }

    @Test
    fun `read scope below limit returns remaining count`() {
        val l = RedisAdminRateLimiter(readLimit = 3, writeLimit = 30, bulkLimit = 5)
        val first = l.tryAcquire("dlq.read", "ip") as AdminRateLimiter.Decision.Allowed
        assertThat(first.remaining).isEqualTo(2L)
        val second = l.tryAcquire("dlq.read", "ip") as AdminRateLimiter.Decision.Allowed
        assertThat(second.remaining).isEqualTo(1L)
    }
}
