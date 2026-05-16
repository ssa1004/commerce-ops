package io.minishop.order.config

import org.springframework.boot.context.properties.ConfigurationProperties

@JvmRecord
@ConfigurationProperties(prefix = "mini-shop.inventory")
data class InventoryClientProperties(
    val url: String,
    val connectTimeoutMs: Long,
    val readTimeoutMs: Long,
)
