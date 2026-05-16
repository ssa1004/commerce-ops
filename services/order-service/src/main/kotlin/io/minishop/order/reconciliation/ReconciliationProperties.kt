package io.minishop.order.reconciliation

import org.springframework.boot.context.properties.ConfigurationProperties

@JvmRecord
@ConfigurationProperties(prefix = "mini-shop.reconciliation")
data class ReconciliationProperties(
    val enabled: Boolean,
    val intervalMs: Long,
    val lookbackMinutes: Long,
    val batchSize: Int,
)
