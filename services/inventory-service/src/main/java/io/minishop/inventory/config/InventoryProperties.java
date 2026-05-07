package io.minishop.inventory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mini-shop.inventory.lock")
public record InventoryProperties(
        String keyPrefix,
        long waitMillis,
        long leaseMillis
) {}
