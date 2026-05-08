package io.minishop.order.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LogIdsTests {

    @Test
    void userId_isStableHash_andDoesNotLeakRawValue() {
        String a = LogIds.userId(42L);
        String b = LogIds.userId(42L);
        String c = LogIds.userId(43L);

        // 같은 입력 → 같은 마스크
        assertThat(a).isEqualTo(b);
        // 다른 입력 → 다른 마스크 (8 자 충돌 확률은 디버그용도엔 충분히 낮음)
        assertThat(a).isNotEqualTo(c);
        // 평문 노출 금지
        assertThat(a).doesNotContain("42");
        assertThat(c).doesNotContain("43");
        // prefix 정해진 모양
        assertThat(a).startsWith("u:").hasSize(10);
    }

    @Test
    void userId_null_isMaskedSentinel() {
        assertThat(LogIds.userId((Long) null)).isEqualTo("u:?");
    }
}
