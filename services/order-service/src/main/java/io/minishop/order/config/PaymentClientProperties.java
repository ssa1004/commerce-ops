package io.minishop.order.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mini-shop.payment")
public record PaymentClientProperties(
        String url,
        long connectTimeoutMs,
        long readTimeoutMs
) {}
