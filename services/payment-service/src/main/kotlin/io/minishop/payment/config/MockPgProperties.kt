package io.minishop.payment.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "mini-shop.mock-pg")
@JvmRecord
data class MockPgProperties(
    val enabled: Boolean,
    val latencyMeanMs: Long,
    val latencyStddevMs: Long,
    val failureRate: Double,
    val timeoutRate: Double,
    val timeoutMs: Long,
)
