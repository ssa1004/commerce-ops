package io.minishop.order.reconciliation;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mini-shop.reconciliation")
public record ReconciliationProperties(
        boolean enabled,
        long intervalMs,
        long lookbackMinutes,
        int batchSize
) {}
