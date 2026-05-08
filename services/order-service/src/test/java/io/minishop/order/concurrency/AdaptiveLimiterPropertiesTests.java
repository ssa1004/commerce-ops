package io.minishop.order.concurrency;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdaptiveLimiterPropertiesTests {

    @Test
    void appliesDefaultsForUnsetFields() {
        AdaptiveLimiterProperties p = new AdaptiveLimiterProperties(true, 0, 0, 0);
        assertThat(p.initialLimit()).isEqualTo(20);
        assertThat(p.minLimit()).isEqualTo(1);
        assertThat(p.maxLimit()).isEqualTo(100);
    }

    @Test
    void clampsMinAboveInitialDown() {
        // min > initial 은 의미 없는 조합 — min 을 initial 로 내린다.
        AdaptiveLimiterProperties p = new AdaptiveLimiterProperties(true, 5, 10, 100);
        assertThat(p.minLimit()).isEqualTo(5);
    }

    @Test
    void clampsMaxBelowInitialUp() {
        // max < initial 도 마찬가지 — max 를 initial 로 올린다.
        AdaptiveLimiterProperties p = new AdaptiveLimiterProperties(true, 50, 1, 30);
        assertThat(p.maxLimit()).isEqualTo(50);
    }

    @Test
    void preservesValidExplicitValues() {
        AdaptiveLimiterProperties p = new AdaptiveLimiterProperties(false, 30, 5, 80);
        assertThat(p.enabled()).isFalse();
        assertThat(p.initialLimit()).isEqualTo(30);
        assertThat(p.minLimit()).isEqualTo(5);
        assertThat(p.maxLimit()).isEqualTo(80);
    }
}
