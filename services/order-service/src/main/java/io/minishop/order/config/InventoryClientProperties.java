package io.minishop.order.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mini-shop.inventory")
public record InventoryClientProperties(
        String url,
        long connectTimeoutMs,
        long readTimeoutMs
) {}
