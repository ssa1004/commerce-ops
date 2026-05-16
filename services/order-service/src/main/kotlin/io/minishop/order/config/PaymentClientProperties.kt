package io.minishop.order.config

import org.springframework.boot.context.properties.ConfigurationProperties

@JvmRecord
@ConfigurationProperties(prefix = "mini-shop.payment")
data class PaymentClientProperties(
    val url: String,
    val connectTimeoutMs: Long,
    val readTimeoutMs: Long,
)
