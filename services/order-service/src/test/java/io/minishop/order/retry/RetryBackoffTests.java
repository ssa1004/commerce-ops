package io.minishop.order.retry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RetryBackoffTests {

    @Test
    void exponentialGrowsUntilMaxCap() {
        // jitter 비활성, base=100, multiplier=2, max=1000 — attempt 1..6 = 100, 200, 400, 800, 1000(캡), 1000(캡)
        RetryProperties p = new RetryProperties(true, 6, 100L, 1000L, 2.0, 0.0, true);
        RetryBackoff b = new RetryBackoff(p, () -> 0.0);
        assertThat(b.delayMillis(1)).isEqualTo(100L);
        assertThat(b.delayMillis(2)).isEqualTo(200L);
        assertThat(b.delayMillis(3)).isEqualTo(400L);
        assertThat(b.delayMillis(4)).isEqualTo(800L);
        assertThat(b.delayMillis(5)).isEqualTo(1000L); // cap
        assertThat(b.delayMillis(6)).isEqualTo(1000L); // cap
    }

    @Test
    void jitterAtRandomZeroIsLowerBound() {
        // jitterFactor=0.5, capped=400 → wait = 400 * (1 - 0.5 + 2*0.5*0) = 200
        RetryProperties p = new RetryProperties(true, 5, 100L, 1000L, 2.0, 0.5, true);
        RetryBackoff b = new RetryBackoff(p, () -> 0.0);
        assertThat(b.delayMillis(3)).isEqualTo(200L);
    }

    @Test
    void jitterAtRandomOneApproachesUpperBound() {
        // jitterFactor=0.5, capped=400, r→1 → wait ≈ 400 * (1 - 0.5 + 2*0.5*1) = 600
        RetryProperties p = new RetryProperties(true, 5, 100L, 1000L, 2.0, 0.5, true);
        RetryBackoff b = new RetryBackoff(p, () -> 0.999999);
        assertThat(b.delayMillis(3)).isBetween(599L, 600L);
    }

    @Test
    void jitterMidRandomGivesCapped() {
        // r=0.5 → wait = capped * (1 - f + 2f * 0.5) = capped (정확히 평균)
        RetryProperties p = new RetryProperties(true, 5, 100L, 1000L, 2.0, 0.5, true);
        RetryBackoff b = new RetryBackoff(p, () -> 0.5);
        assertThat(b.delayMillis(2)).isEqualTo(200L);
    }

    @Test
    void attemptZeroOrNegativeTreatedAsOne() {
        RetryProperties p = new RetryProperties(true, 3, 100L, 1000L, 2.0, 0.0, true);
        RetryBackoff b = new RetryBackoff(p, () -> 0.0);
        assertThat(b.delayMillis(0)).isEqualTo(100L);
        assertThat(b.delayMillis(-3)).isEqualTo(100L);
    }

    @Test
    void neverReturnsNegativeOrLessThanZero() {
        // jitterFactor 가 캡 (0.99) 일 때도 wait 는 음수가 되지 않아야 함 (잘못된 sleep 방지).
        RetryProperties p = new RetryProperties(true, 5, 100L, 1000L, 2.0, 1.0 /* → 0.99 cap */, true);
        RetryBackoff b = new RetryBackoff(p, () -> 0.0);
        assertThat(b.delayMillis(3)).isGreaterThanOrEqualTo(0L);
    }
}
