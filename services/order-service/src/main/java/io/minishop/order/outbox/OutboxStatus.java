package io.minishop.order.outbox;

public enum OutboxStatus {
    PENDING,
    SENT,
    FAILED
}
