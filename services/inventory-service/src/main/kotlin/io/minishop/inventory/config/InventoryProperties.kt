package io.minishop.inventory.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "mini-shop.inventory.lock")
@JvmRecord
data class InventoryProperties(
    val keyPrefix: String,
    val waitMillis: Long,
    val leaseMillis: Long,
)
