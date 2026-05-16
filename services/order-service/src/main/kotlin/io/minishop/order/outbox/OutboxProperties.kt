package io.minishop.order.outbox

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * outbox 폴러 설정. nested [Poller] 는 [JvmRecord] data class — Java caller 의 record-style
 * accessor (`props.poller().sendTimeoutMs()`) 호환.
 *
 * NOTE: Java 의 `Poller` 는 compact constructor 로 sendTimeoutMs 가 0 이하면 5_000 기본값으로
 * 보정 (legacy config 호환). Kotlin data class 는 val 재대입 불가 → 일반 class + init 정규화로
 * 동일 시멘틱 재현.
 */
@ConfigurationProperties(prefix = "mini-shop.outbox")
class OutboxProperties(
    poller: Poller,
) {
    @get:JvmName("poller")
    val poller: Poller = poller

    override fun equals(other: Any?): Boolean = this === other || (other is OutboxProperties && poller == other.poller)
    override fun hashCode(): Int = poller.hashCode()
    override fun toString(): String = "OutboxProperties(poller=$poller)"

    class Poller(
        enabled: Boolean,
        intervalMs: Long,
        batchSize: Int,
        maxAttempts: Int,
        /**
         * Kafka 한 건 발행을 기다릴 최대 시간 (ms). 초과하면 발행 실패로 간주하고 다음 행으로
         * 진행. 트랜잭션 + FOR UPDATE SKIP LOCKED 가 잡혀 있는 동안 Kafka 가 느려지면 다른
         * poller 까지 줄줄이 묶이는 사고를 방지하는 안전장치.
         */
        sendTimeoutMs: Long,
    ) {
        @get:JvmName("enabled")
        val enabled: Boolean = enabled

        @get:JvmName("intervalMs")
        val intervalMs: Long = intervalMs

        @get:JvmName("batchSize")
        val batchSize: Int = batchSize

        @get:JvmName("maxAttempts")
        val maxAttempts: Int = maxAttempts

        @get:JvmName("sendTimeoutMs")
        val sendTimeoutMs: Long

        init {
            this.sendTimeoutMs = if (sendTimeoutMs <= 0L) 5_000L else sendTimeoutMs   // legacy config 호환 — 기본 5초
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Poller) return false
            return enabled == other.enabled &&
                intervalMs == other.intervalMs &&
                batchSize == other.batchSize &&
                maxAttempts == other.maxAttempts &&
                sendTimeoutMs == other.sendTimeoutMs
        }

        override fun hashCode(): Int {
            var r = enabled.hashCode()
            r = 31 * r + intervalMs.hashCode()
            r = 31 * r + batchSize
            r = 31 * r + maxAttempts
            r = 31 * r + sendTimeoutMs.hashCode()
            return r
        }

        override fun toString(): String =
            "Poller(enabled=$enabled, intervalMs=$intervalMs, batchSize=$batchSize, " +
                "maxAttempts=$maxAttempts, sendTimeoutMs=$sendTimeoutMs)"
    }
}
