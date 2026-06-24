package io.minishop.order.saga

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import io.minishop.order.domain.SagaStep
import io.minishop.order.repository.SagaStepRepository
import io.minishop.order.service.InventoryClient
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

/**
 * 영속 saga_step 을 읽어 보상(역방향)을 실행하는 엔진.
 *
 * in-memory 리스트 대신 DB 에 기록된 step 을 *진실의 원천* 으로 본다. 따라서:
 * - 정상 실패(결제 거절 등) 시 같은 요청에서 호출하든,
 * - 크래시 후 [SagaRecoveryJob] 이 나중에 호출하든
 * 동일하게 동작한다.
 *
 * 동작:
 * 1. orderId 의 step 을 seq 역순으로 조회(정방향의 역순).
 * 2. 아직 보상 안 된(STARTED/DONE/COMPENSATION_FAILED) step 만 대상.
 * 3. 재시도 상한([maxCompensationAttempts]) 초과 step 은 더 안 건드리고 운영자 신호(metric).
 * 4. 각 step 의 보상을 [dispatch] 로 실행 → 성공 COMPENSATED / 실패 COMPENSATION_FAILED.
 *
 * 멱등성: inventory release 는 안 잡힌 예약을 풀어도 no-op 이라, 같은 step 을 두 번 보상해도 안전.
 * 원격 호출은 DB 트랜잭션 밖에서 하고, 상태 마킹만 짧은 트랜잭션으로 처리한다.
 *
 * 참고: 현재 유일한 보상(inventory release)은 InventoryClient 가 인프라 오류를 *삼키고* 로그만
 * 남기는 best-effort 라(보상 실패는 reconciliation 잡이 사후 탐지), 실제로는 거의 COMPENSATED 로
 * 끝난다. COMPENSATION_FAILED → 재시도/exhausted 경로는 향후 throw 하는 보상(결제 취소 등)을
 * 위한 일반 골격이며 단위 테스트로 검증한다.
 */
@Component
class CompensationRunner(
    private val sagaStepRepository: SagaStepRepository,
    private val inventoryClient: InventoryClient,
    private val tx: TransactionTemplate,
    private val meterRegistry: MeterRegistry,
    @Value("\${mini-shop.saga-recovery.max-compensation-attempts:5}")
    private val maxCompensationAttempts: Int,
) {

    data class Summary(val compensated: Int, val failed: Int, val exhausted: Int) {
        val allCompensated: Boolean get() = failed == 0 && exhausted == 0
    }

    fun compensate(orderId: Long): Summary {
        val targets = sagaStepRepository.findByOrderIdOrderBySeqDesc(orderId)
            .filter { it.needsCompensation() }

        var compensated = 0
        var failed = 0
        var exhausted = 0

        for (step in targets) {
            if (step.compensationAttempts >= maxCompensationAttempts) {
                exhausted++
                meterRegistry.counter(
                    "order.saga.compensation",
                    Tags.of("step", step.stepName ?: "?", "result", "exhausted"),
                ).increment()
                log.error(
                    "compensation exhausted order={} step={} attempts={} — 운영자 개입 필요",
                    orderId, step.stepName, step.compensationAttempts,
                )
                continue
            }

            try {
                dispatch(step, orderId) // 원격 호출 — 트랜잭션 밖
                tx.executeWithoutResult { _ ->
                    sagaStepRepository.findById(step.id!!).ifPresent {
                        it.beginCompensationAttempt()
                        it.markCompensated()
                    }
                }
                compensated++
                meterRegistry.counter(
                    "order.saga.compensation",
                    Tags.of("step", step.stepName ?: "?", "result", "ok"),
                ).increment()
            } catch (e: Exception) {
                tx.executeWithoutResult { _ ->
                    sagaStepRepository.findById(step.id!!).ifPresent {
                        it.beginCompensationAttempt()
                        it.markCompensationFailed()
                    }
                }
                failed++
                meterRegistry.counter(
                    "order.saga.compensation",
                    Tags.of("step", step.stepName ?: "?", "result", "failed"),
                ).increment()
                log.warn(
                    "compensation failed order={} step={} attempt={}: {}",
                    orderId, step.stepName, step.compensationAttempts + 1, e.message,
                )
            }
        }
        return Summary(compensated, failed, exhausted)
    }

    private fun dispatch(step: SagaStep, orderId: Long) {
        when (step.compensation) {
            SagaSteps.INVENTORY_RELEASE -> inventoryClient.release(productIdOf(step), orderId)
            else -> throw IllegalStateException("unknown compensation: ${step.compensation}")
        }
    }

    private fun productIdOf(step: SagaStep): Long =
        step.payload?.trim()?.toLongOrNull()
            ?: throw IllegalStateException("invalid payload for ${step.stepName}: ${step.payload}")

    companion object {
        private val log = LoggerFactory.getLogger(CompensationRunner::class.java)
    }
}
