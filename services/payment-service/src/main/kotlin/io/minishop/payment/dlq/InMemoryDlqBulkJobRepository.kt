package io.minishop.payment.dlq

import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

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
    ): DlqBulkJobResponse? = store.computeIfPresent(jobId) { _, current -> transform(current) }

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
