package io.minishop.order.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mini-shop.outbox")
public record OutboxProperties(
        Poller poller
) {
    public record Poller(
            boolean enabled,
            long intervalMs,
            int batchSize,
            int maxAttempts
    ) {}
}
