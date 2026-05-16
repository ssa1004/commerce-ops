package io.minishop.inventory.service

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer
import io.minishop.inventory.config.InventoryProperties
import org.redisson.api.RedissonClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

@Service
class DistributedLockService(
    private val redisson: RedissonClient,
    private val props: InventoryProperties,
    private val meterRegistry: MeterRegistry,
) {

    fun <T> withLock(key: String, action: Supplier<T>): T {
        val fullKey = "${props.keyPrefix}:$key"
        val lock = redisson.getLock(fullKey)

        val acquireSample = Timer.start(meterRegistry)
        val acquired: Boolean = try {
            lock.tryLock(props.waitMillis, props.leaseMillis, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            recordOutcome(acquireSample, "interrupted")
            throw LockAcquisitionException("Interrupted while acquiring lock for $fullKey", e)
        }

        if (!acquired) {
            recordOutcome(acquireSample, "timeout")
            throw LockAcquisitionException("Lock acquisition timed out for $fullKey")
        }
        recordOutcome(acquireSample, "acquired")

        try {
            return action.get()
        } finally {
            if (lock.isHeldByCurrentThread) {
                lock.unlock()
            } else {
                // 여기 들어온다는 건 작업이 lease (Redis 락의 자동 만료 시간) 보다 오래 걸렸고,
                // 그 사이 락이 만료되어 다른 요청이 이미 같은 자원에 끼어들었을 수 있다는 뜻.
                //
                // 동시 진입 자체는 *이미* 일어난 상태라 여기서는 막을 수 없다. 진짜 안전망은 Inventory
                // 엔티티의 JPA @Version 낙관적 락 (둘이 같이 갱신하면 늦게 커밋하는 쪽이 실패) — 자원
                // 무결성은 그쪽이 책임진다.
                //
                // 이 카운터는 그 위험한 윈도우가 운영 중 얼마나 자주 열리는지 추적하는 신호. 값이 꾸준히
                // 올라가면 lease 가 작업 평균 시간 대비 너무 짧다는 뜻이라 튜닝 신호로 쓴다.
                log.warn("Lock {} was no longer held when releasing — likely lease expired", fullKey)
                meterRegistry.counter("inventory.lock.lease_expired", Tags.of("key", "product"))
                    .increment()
            }
        }
    }

    private fun recordOutcome(sample: Timer.Sample, outcome: String) {
        sample.stop(meterRegistry.timer("inventory.lock.acquire", Tags.of("outcome", outcome)))
    }

    class LockAcquisitionException : RuntimeException {
        constructor(message: String) : super(message)
        constructor(message: String, cause: Throwable) : super(message, cause)
    }

    companion object {
        private val log = LoggerFactory.getLogger(DistributedLockService::class.java)
    }
}
