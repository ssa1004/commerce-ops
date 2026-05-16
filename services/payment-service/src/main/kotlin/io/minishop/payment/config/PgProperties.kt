package io.minishop.payment.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "mini-shop.pg")
@JvmRecord
data class PgProperties(
    val url: String,
    val connectTimeoutMs: Long,
    val readTimeoutMs: Long,
)
