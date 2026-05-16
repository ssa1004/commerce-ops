package io.minishop.order.outbox

enum class OutboxStatus {
    PENDING,
    SENT,
    FAILED
}
