package io.minishop.inventory.dlq

import io.minishop.inventory.config.InventoryProperties
import org.redisson.api.RedissonClient
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

/**
 * inventory-service 의 [DlqDistributedLock] 어댑터 — Redisson 의 RLock 을 사용해
 * DLQ replay 의 lock 재획득을 *동일한 키 prefix* 와 *동일한 wait/lease 설정* 으로 수행.
 *
 * 키 prefix 는 [io.minishop.inventory.config.InventoryProperties.keyPrefix] — 정상 reserve/release
 * 경로와 한 prefix 를 공유해야 같은 자원의 락이 *겹친다* (즉, replay 중에 새 reserve 가 끼어들지
 * 않음).
 *
 * wait / lease 는 정상 경로 ([io.minishop.inventory.service.DistributedLockService]) 와 동일 —
 * DLQ replay 에서만 더 길게 잡으면 정상 reserve 가 DLQ replay 뒤에서 대기열에 갇히는 사고가 가능.
 *
 * Redisson client 가 등록되어 있을 때만 활성화 (테스트에선 fake [DlqDistributedLock] 으로 대체).
 */
@Component
@ConditionalOnBean(RedissonClient::class)
@ConditionalOnMissingBean(name = ["dlqDistributedLock"])
class RedissonDlqDistributedLock(
    private val redisson: RedissonClient,
    private val props: InventoryProperties,
) : DlqDistributedLock {

    private val log = LoggerFactory.getLogger(RedissonDlqDistributedLock::class.java)

    override fun <T> withLock(key: String, action: () -> T): DlqDistributedLock.LockResult<T> {
        val fullKey = "${props.keyPrefix}:$key"
        val lock = redisson.getLock(fullKey)
        val acquired = try {
            lock.tryLock(props.waitMillis, props.leaseMillis, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            log.warn("DLQ replay lock interrupted key={} err={}", fullKey, e.message)
            return DlqDistributedLock.LockResult.Timeout
        }
        if (!acquired) {
            return DlqDistributedLock.LockResult.Timeout
        }
        return try {
            DlqDistributedLock.LockResult.Acquired(action())
        } finally {
            if (lock.isHeldByCurrentThread) {
                lock.unlock()
            } else {
                log.warn("DLQ replay lock no longer held when releasing key={}", fullKey)
            }
        }
    }
}
