package io.minishop.inventory.dlq

import org.redisson.api.RedissonClient
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.stereotype.Component

/**
 * Redisson 이 없는 환경 (테스트의 일부 / 로컬 데모 일시 중단 등) 의 fallback.
 * 락이 *없다고 가정* — 의도적으로 동시 reserve 의 위험을 노출하지 않도록, replay 결과는
 * 항상 acquired=true 로 응답하되 로그에 경고를 남긴다.
 *
 * 운영에서는 RedissonClient 가 항상 등록되어 [RedissonDlqDistributedLock] 이 활성화됨.
 */
@Component("dlqDistributedLock")
@ConditionalOnMissingBean(RedissonClient::class)
class NoOpDlqDistributedLock : DlqDistributedLock {
    override fun <T> withLock(key: String, action: () -> T): DlqDistributedLock.LockResult<T> {
        return DlqDistributedLock.LockResult.Acquired(action())
    }
}
