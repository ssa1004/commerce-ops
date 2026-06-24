package io.minishop.order.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.PrePersist
import jakarta.persistence.Table
import java.time.Instant

/**
 * 영속 SAGA step — 한 주문의 정방향 단계 하나와 그 보상 정보를 DB 에 기록한다.
 *
 * in-memory 보상 리스트의 한계(크래시 시 유실)를 없애기 위한 핵심 엔티티. 실패 시
 * [io.minishop.order.saga.CompensationRunner] 가 [seq] 역순으로 not-COMPENSATED step 을
 * 조회해 보상한다.
 */
@Entity
@Table(name = "saga_step")
class SagaStep protected constructor() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @get:JvmName("getId")
    var id: Long? = null
        private set

    @Column(name = "order_id", nullable = false)
    @get:JvmName("getOrderId")
    var orderId: Long? = null
        private set

    @Column(nullable = false)
    @get:JvmName("getSeq")
    var seq: Int = 0
        private set

    @Column(name = "step_name", nullable = false, length = 64)
    @get:JvmName("getStepName")
    var stepName: String? = null
        private set

    @Column(nullable = false, length = 64)
    @get:JvmName("getCompensation")
    var compensation: String? = null
        private set

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    @get:JvmName("getStatus")
    var status: SagaStepStatus? = null
        private set

    @Column(length = 1024)
    @get:JvmName("getPayload")
    var payload: String? = null
        private set

    @Column(name = "compensation_attempts", nullable = false)
    @get:JvmName("getCompensationAttempts")
    var compensationAttempts: Int = 0
        private set

    @Column(name = "created_at", nullable = false)
    @get:JvmName("getCreatedAt")
    var createdAt: Instant? = null
        private set

    @Column(name = "compensated_at")
    @get:JvmName("getCompensatedAt")
    var compensatedAt: Instant? = null
        private set

    /** 정방향 호출 성공 확정. */
    fun markDone() {
        this.status = SagaStepStatus.DONE
    }

    /** 정방향 호출이 동기적으로 실패(확정적으로 아무것도 안 일어남) — 보상 대상에서 제외. */
    fun markAborted() {
        this.status = SagaStepStatus.ABORTED
    }

    /** 보상 시도 직전 — 시도 횟수 증가(성공/실패 모두 1회로 집계). */
    fun beginCompensationAttempt() {
        this.compensationAttempts += 1
    }

    /** 보상 완료. */
    fun markCompensated() {
        this.status = SagaStepStatus.COMPENSATED
        this.compensatedAt = Instant.now()
    }

    /** 보상 실패 — 복구 잡이 재시도 대상으로 본다. */
    fun markCompensationFailed() {
        this.status = SagaStepStatus.COMPENSATION_FAILED
    }

    /** 아직 보상되지 않은(보상 대상) step 인가. */
    fun needsCompensation(): Boolean =
        status == SagaStepStatus.STARTED ||
            status == SagaStepStatus.DONE ||
            status == SagaStepStatus.COMPENSATION_FAILED

    @PrePersist
    internal fun onCreate() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now()
        }
    }

    companion object {
        /** 정방향 호출 직전 의도 로그(STARTED) 로 새 step 을 만든다. */
        @JvmStatic
        fun started(
            orderId: Long,
            seq: Int,
            stepName: String,
            compensation: String,
            payload: String?,
        ): SagaStep {
            val step = SagaStep()
            step.orderId = orderId
            step.seq = seq
            step.stepName = stepName
            step.compensation = compensation
            step.payload = payload
            step.status = SagaStepStatus.STARTED
            step.compensationAttempts = 0
            return step
        }
    }
}
