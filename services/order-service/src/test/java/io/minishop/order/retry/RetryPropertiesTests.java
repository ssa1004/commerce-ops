package io.minishop.order.retry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RetryPropertiesTests {

    @Test
    void appliesDefaultsForUnsetFields() {
        RetryProperties p = new RetryProperties(true, 0, 0L, 0L, 0.0, 0.0, true);
        assertThat(p.maxAttempts()).isEqualTo(3);
        assertThat(p.baseDelayMs()).isEqualTo(200L);
        assertThat(p.maxDelayMs()).isEqualTo(2000L);
        // multiplier < 1 은 의미 없음 (감쇠) → 2.0 으로 보정.
        assertThat(p.multiplier()).isEqualTo(2.0);
    }

    @Test
    void clampsNegativeJitterToZero() {
        RetryProperties p = new RetryProperties(true, 3, 200L, 2000L, 2.0, -0.5, true);
        assertThat(p.jitterFactor()).isEqualTo(0.0);
    }

    @Test
    void capsExcessiveJitterTo099() {
        RetryProperties p = new RetryProperties(true, 3, 200L, 2000L, 2.0, 1.5, true);
        // jitterFactor=1 이면 wait 가 0 또는 음수 가능 — 0.99 로 캡.
        assertThat(p.jitterFactor()).isEqualTo(0.99);
    }

    @Test
    void clampsBaseDelayAboveMaxDown() {
        // base > max 는 의미 없음 — base 를 max 로 내림.
        RetryProperties p = new RetryProperties(true, 3, 5000L, 2000L, 2.0, 0.5, true);
        assertThat(p.baseDelayMs()).isEqualTo(2000L);
    }

    @Test
    void preservesValidExplicitValues() {
        RetryProperties p = new RetryProperties(false, 5, 100L, 5000L, 3.0, 0.3, false);
        assertThat(p.enabled()).isFalse();
        assertThat(p.maxAttempts()).isEqualTo(5);
        assertThat(p.baseDelayMs()).isEqualTo(100L);
        assertThat(p.maxDelayMs()).isEqualTo(5000L);
        assertThat(p.multiplier()).isEqualTo(3.0);
        assertThat(p.jitterFactor()).isEqualTo(0.3);
        assertThat(p.retryOn5xx()).isFalse();
    }
}
