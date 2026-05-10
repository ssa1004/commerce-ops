package io.minishop.jfr;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class JfrRecorderPropertiesTests {

    @Test
    void appliesDefaultsForUnsetFields() {
        JfrRecorderProperties props = new JfrRecorderProperties(true, null, 0, null, null, null, false);
        assertThat(props.rollover()).isEqualTo(Duration.ofMinutes(5));
        assertThat(props.maxRetained()).isEqualTo(24);
        assertThat(props.maxTotalSize()).isEqualTo(DataSize.ofMegabytes(500));
        assertThat(props.dumpDirectory()).isEqualTo("/tmp/jfr");
        assertThat(props.settings()).isEqualTo("default");
        assertThat(props.maskSensitiveEvents()).isFalse();
    }

    @Test
    void preservesExplicitValues() {
        JfrRecorderProperties props = new JfrRecorderProperties(
                true, Duration.ofMinutes(1), 6, DataSize.ofMegabytes(128), "/var/jfr", "profile", true
        );
        assertThat(props.rollover()).isEqualTo(Duration.ofMinutes(1));
        assertThat(props.maxRetained()).isEqualTo(6);
        assertThat(props.maxTotalSize()).isEqualTo(DataSize.ofMegabytes(128));
        assertThat(props.dumpDirectory()).isEqualTo("/var/jfr");
        assertThat(props.settings()).isEqualTo("profile");
        assertThat(props.maskSensitiveEvents()).isTrue();
    }

    @Test
    void rejectsZeroOrNegativeRollover() {
        JfrRecorderProperties zero = new JfrRecorderProperties(true, Duration.ZERO, 0, null, null, null, false);
        assertThat(zero.rollover()).isEqualTo(Duration.ofMinutes(5));

        JfrRecorderProperties neg = new JfrRecorderProperties(true, Duration.ofSeconds(-1), 0, null, null, null, false);
        assertThat(neg.rollover()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void rejectsZeroOrNegativeMaxTotalSize() {
        JfrRecorderProperties zero = new JfrRecorderProperties(
                true, null, 0, DataSize.ofBytes(0), null, null, false);
        assertThat(zero.maxTotalSize()).isEqualTo(DataSize.ofMegabytes(500));

        JfrRecorderProperties neg = new JfrRecorderProperties(
                true, null, 0, DataSize.ofBytes(-1), null, null, false);
        assertThat(neg.maxTotalSize()).isEqualTo(DataSize.ofMegabytes(500));
    }
}
