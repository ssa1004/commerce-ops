package io.minishop.order.saga

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.minishop.order.domain.SagaStep
import io.minishop.order.domain.SagaStepStatus
import io.minishop.order.repository.SagaStepRepository
import io.minishop.order.service.InventoryClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.then
import org.mockito.BDDMockito.willThrow
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.util.Optional

/**
 * 영속 saga_step 기반 보상 엔진의 핵심 로직 검증 (DB 없이 mock 으로).
 * - seq 역순 보상
 * - 보상 실패 → COMPENSATION_FAILED 기록
 * - 재시도 상한 초과 step 은 건너뜀(exhausted)
 * - 이미 COMPENSATED 된 step 은 멱등하게 건너뜀
 */
class CompensationRunnerTest {

    private val repo = mock(SagaStepRepository::class.java)
    private val inventory = mock(InventoryClient::class.java)
    private val tx = TransactionTemplate(noOpTxManager())
    private val runner = CompensationRunner(repo, inventory, tx, SimpleMeterRegistry(), MAX_ATTEMPTS)

    @Test
    fun `보상은 seq 역순으로 실행되고 모든 step 이 COMPENSATED 된다`() {
        val stepA = doneStep(id = 1L, seq = 0, productId = 10L)
        val stepB = doneStep(id = 2L, seq = 1, productId = 20L)
        // repository 는 seq 역순(desc)으로 반환 — 보상은 정방향의 역순.
        given(repo.findByOrderIdOrderBySeqDesc(ORDER_ID)).willReturn(listOf(stepB, stepA))
        given(repo.findById(1L)).willReturn(Optional.of(stepA))
        given(repo.findById(2L)).willReturn(Optional.of(stepB))

        val summary = runner.compensate(ORDER_ID)

        assertThat(summary.compensated).isEqualTo(2)
        assertThat(summary.failed).isZero()
        assertThat(summary.allCompensated).isTrue()
        assertThat(stepA.status).isEqualTo(SagaStepStatus.COMPENSATED)
        assertThat(stepB.status).isEqualTo(SagaStepStatus.COMPENSATED)
        // 역순: product 20(seq1) → product 10(seq0)
        val ord = inOrder(inventory)
        then(inventory).should(ord).release(20L, ORDER_ID)
        then(inventory).should(ord).release(10L, ORDER_ID)
    }

    @Test
    fun `보상 호출이 실패하면 COMPENSATION_FAILED 로 기록되고 summary_failed 가 증가한다`() {
        val step = doneStep(id = 1L, seq = 0, productId = 10L)
        given(repo.findByOrderIdOrderBySeqDesc(ORDER_ID)).willReturn(listOf(step))
        given(repo.findById(1L)).willReturn(Optional.of(step))
        willThrow(RuntimeException("release boom")).given(inventory).release(anyLong(), anyLong())

        val summary = runner.compensate(ORDER_ID)

        assertThat(summary.compensated).isZero()
        assertThat(summary.failed).isEqualTo(1)
        assertThat(summary.allCompensated).isFalse()
        assertThat(step.status).isEqualTo(SagaStepStatus.COMPENSATION_FAILED)
        assertThat(step.compensationAttempts).isEqualTo(1)
    }

    @Test
    fun `재시도 상한을 넘긴 step 은 건너뛴다(exhausted) — 보상 호출 안 함`() {
        val step = doneStep(id = 1L, seq = 0, productId = 10L)
        repeat(MAX_ATTEMPTS) { step.beginCompensationAttempt() } // attempts == MAX
        given(repo.findByOrderIdOrderBySeqDesc(ORDER_ID)).willReturn(listOf(step))

        val summary = runner.compensate(ORDER_ID)

        assertThat(summary.exhausted).isEqualTo(1)
        assertThat(summary.compensated).isZero()
        then(inventory).should(never()).release(anyLong(), anyLong())
    }

    @Test
    fun `이미 COMPENSATED 된 step 은 멱등하게 건너뛴다`() {
        val step = doneStep(id = 1L, seq = 0, productId = 10L)
        step.markCompensated()
        given(repo.findByOrderIdOrderBySeqDesc(ORDER_ID)).willReturn(listOf(step))

        val summary = runner.compensate(ORDER_ID)

        assertThat(summary.compensated).isZero()
        assertThat(summary.failed).isZero()
        assertThat(summary.exhausted).isZero()
        then(inventory).should(never()).release(anyLong(), anyLong())
    }

    private fun doneStep(id: Long, seq: Int, productId: Long): SagaStep {
        val step = SagaStep.started(
            ORDER_ID, seq, SagaSteps.INVENTORY_RESERVE, SagaSteps.INVENTORY_RELEASE, productId.toString(),
        )
        step.markDone()
        val field = SagaStep::class.java.getDeclaredField("id")
        field.isAccessible = true
        field.set(step, id)
        return step
    }

    private fun noOpTxManager(): PlatformTransactionManager {
        val m = mock(PlatformTransactionManager::class.java)
        given(m.getTransaction(any())).willReturn(SimpleTransactionStatus())
        return m
    }

    companion object {
        private const val ORDER_ID = 42L
        private const val MAX_ATTEMPTS = 5
    }
}
