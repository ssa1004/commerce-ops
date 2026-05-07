package io.minishop.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mini-shop.mock-pg")
public record MockPgProperties(
        boolean enabled,
        long latencyMeanMs,
        long latencyStddevMs,
        double failureRate,
        double timeoutRate,
        long timeoutMs
) {}
