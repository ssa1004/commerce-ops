package io.minishop.correlation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationMdcPropertiesTests {

    @Test
    void appliesDefaultsForBlankKeys() {
        CorrelationMdcProperties p = new CorrelationMdcProperties(true, null, "");
        assertThat(p.traceIdKey()).isEqualTo("trace_id");
        assertThat(p.spanIdKey()).isEqualTo("span_id");
    }

    @Test
    void preservesExplicitKeys() {
        CorrelationMdcProperties p = new CorrelationMdcProperties(true, "tid", "sid");
        assertThat(p.traceIdKey()).isEqualTo("tid");
        assertThat(p.spanIdKey()).isEqualTo("sid");
    }
}
