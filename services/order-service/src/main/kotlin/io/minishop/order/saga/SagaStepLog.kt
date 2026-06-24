package io.minishop.order.saga

import io.minishop.order.domain.SagaStep
import io.minishop.order.repository.SagaStepRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

/**
 * SAGA step 영속의 좁은 진입점. 정방향 단계마다 호출자가:
 *   1) 원격 호출 *직전* [start] 로 STARTED 의도 로그를 남기고,
 *   2) 호출 성공 후 [markDone] 으로 DONE 확정한다.
 *
 * STARTED 를 먼저 남기는 이유: reserve(원격) 성공과 step 기록(로컬)은 원자적이지 않다. 만약
 * reserve 성공 후 기록 전에 죽으면 고아 예약이 추적 불가다. 그래서 호출 *전* 에 STARTED 를
 * 남겨두면, 크래시 후 복구 잡이 그 step 을 보상(release)할 수 있다 — release 는 멱등이라
 * 실제로 안 잡힌 예약을 풀어도 무해하다.
 */
@Component
class SagaStepLog(
    private val repository: SagaStepRepository,
    private val tx: TransactionTemplate,
) {

    /** 정방향 호출 직전 STARTED step 기록. 반환된 id 로 이후 [markDone]. */
    fun start(orderId: Long, seq: Int, stepName: String, compensation: String, payload: String?): Long =
        tx.execute { _ ->
            repository.save(SagaStep.started(orderId, seq, stepName, compensation, payload)).id!!
        }!!

    /** 정방향 호출 성공 확정. */
    fun markDone(stepId: Long) {
        tx.executeWithoutResult { _ ->
            repository.findById(stepId).ifPresent { it.markDone() }
        }
    }

    /**
     * 정방향 호출이 동기적으로 실패(예외) — 확정적으로 아무것도 안 일어났으므로 보상 대상에서 제외.
     * (예외 없이 프로세스가 죽은 경우엔 STARTED 로 남아 복구 잡이 보상한다.)
     */
    fun markAborted(stepId: Long) {
        tx.executeWithoutResult { _ ->
            repository.findById(stepId).ifPresent { it.markAborted() }
        }
    }
}
