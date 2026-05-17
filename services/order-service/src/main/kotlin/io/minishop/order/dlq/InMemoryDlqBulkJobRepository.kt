package io.minishop.order.dlq

import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * DLQ bulk job 상태의 *in-memory* 저장소. 단일 인스턴스 가정.
 *
 * 운영은 Redis Hash 로 교체 — 인스턴스 재시작 / 멀티 인스턴스 폴링 시 결과 손실 방지.
 * 본 step 에서는 4 service 검증 시 충분했던 in-memory 로 시작 (port 만 같으면 교체 비용 0).
 *
 * GC 안전성을 위해 1 시간 보존 (시간이 지난 job 은 자동 제거). 더 긴 추적은 audit 로그 grep.
 */
@Component
class InMemoryDlqBulkJobRepository : DlqBulkJobRepository {

    private val store = ConcurrentHashMap<String, DlqBulkJobResponse>()

    override fun create(initial: DlqBulkJobResponse): DlqBulkJobResponse {
        store[initial.jobId] = initial
        evictOld()
        return initial
    }

    override fun update(
        jobId: String,
        transform: (DlqBulkJobResponse) -> DlqBulkJobResponse,
    ): DlqBulkJobResponse? {
        return store.computeIfPresent(jobId) { _, current -> transform(current) }
    }

    override fun find(jobId: String): DlqBulkJobResponse? = store[jobId]

    private fun evictOld() {
        if (store.size <= MAX_RETAINED) return
        val cutoff = java.time.Instant.now().minusSeconds(RETENTION_SECONDS)
        store.entries.removeIf { it.value.startedAt.isBefore(cutoff) }
    }

    companion object {
        const val MAX_RETAINED = 1000
        const val RETENTION_SECONDS = 3600L
    }
}
