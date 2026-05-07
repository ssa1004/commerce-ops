package io.minishop.slowquery;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "mini-shop.slow-query")
public record SlowQueryDetectorProperties(
        boolean enabled,
        Duration slowThreshold,
        int nPlusOneThreshold,
        boolean captureStacktrace,
        int stacktraceDepth
) {
    public SlowQueryDetectorProperties {
        if (slowThreshold == null) slowThreshold = Duration.ofMillis(200);
        if (nPlusOneThreshold <= 0) nPlusOneThreshold = 5;
        if (stacktraceDepth <= 0) stacktraceDepth = 8;
    }
}
