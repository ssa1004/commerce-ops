package io.minishop.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mini-shop.pg")
public record PgProperties(
        String url,
        long connectTimeoutMs,
        long readTimeoutMs
) {}
