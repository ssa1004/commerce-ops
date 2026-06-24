package io.minishop.order.saga

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * stuck SAGA 복구 잡 설정. 프로세스가 reserve~compensate 사이에서 죽어 PENDING 에 멈춘 주문을
 * 주기적으로 찾아 보상 + FAILED 처리한다.
 */
@JvmRecord
@ConfigurationProperties(prefix = "mini-shop.saga-recovery")
data class SagaRecoveryProperties(
    val enabled: Boolean,
    val intervalMs: Long,
    /** 이 시간(초) 넘게 PENDING 인 주문을 "멈춤"으로 간주. 정상 흐름은 동기라 즉시 PAID/FAILED. */
    val stuckAfterSeconds: Long,
    val batchSize: Int,
    /** 한 step 의 보상 재시도 상한. 초과하면 더 시도하지 않고 운영자 신호(metric)로 남긴다. */
    val maxCompensationAttempts: Int,
)
