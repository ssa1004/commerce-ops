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
            int maxAttempts,
            /**
             * Kafka 한 건 발행을 기다릴 최대 시간 (ms). 초과하면 발행 실패로 간주하고 다음 행으로
             * 진행. 트랜잭션 + FOR UPDATE SKIP LOCKED 가 잡혀 있는 동안 Kafka 가 느려지면 다른
             * poller 까지 줄줄이 묶이는 사고를 방지하는 안전장치.
             */
            long sendTimeoutMs
    ) {
        public Poller {
            if (sendTimeoutMs <= 0) sendTimeoutMs = 5_000L;   // legacy config 호환 — 기본 5초
        }
    }
}
