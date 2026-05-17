package io.minishop.inventory.dlq

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

class DlqAdminServiceTests {

    private lateinit var repo: InMemoryDlqMessageRepository
    private lateinit var lock: FakeLock
    private lateinit var useCase: InventoryDlqUseCase
    private lateinit var jobs: InMemoryDlqBulkJobRepository
    private lateinit var bulk: DlqBulkJobService
    private lateinit var audit: CapturingAudit
    private lateinit var service: DlqAdminService

    @BeforeEach
    fun setUp() {
        repo = InMemoryDlqMessageRepository()
        lock = FakeLock()
        useCase = InventoryDlqUseCase(repo, lock)
        jobs = InMemoryDlqBulkJobRepository()
        bulk = DlqBulkJobService(jobs)
        audit = CapturingAudit()
        service = DlqAdminService(repo, useCase, audit, bulk)
    }

    @Test
    fun `RESERVE_FAILED replay reacquires the distributed lock for the productId`() {
        repo.put(sampleMessage("m-1", DlqSource.RESERVE_FAILED, productId = 1001L, orderId = 7L))

        val response = service.replay("m-1", "alice", "k-1")

        assertThat(response.ok).isTrue
        assertThat(response.lockAcquired).isTrue
        assertThat(lock.acquiredKeys).containsExactly("product:1001")
        val e = audit.events.single()
        assertThat(e.productId).isEqualTo(1001L)
        assertThat(e.extra["lockAcquired"]).isEqualTo("true")
    }

    @Test
    fun `replay fails with LOCK_TIMEOUT when distributed lock cannot be acquired`() {
        repo.put(sampleMessage("m-2", DlqSource.RESERVE_FAILED, productId = 2002L, orderId = 8L))
        lock.alwaysTimeout = true

        val response = service.replay("m-2", "alice", "k-2")

        assertThat(response.ok).isFalse
        assertThat(response.reason).isEqualTo("LOCK_TIMEOUT")
        assertThat(response.lockAcquired).isFalse
    }

    @Test
    fun `KAFKA_CONSUME replay does not touch the distributed lock`() {
        repo.put(sampleMessage("kc-1", DlqSource.KAFKA_CONSUME, productId = 3003L, orderId = 9L))
        val response = service.replay("kc-1", "alice", "k")
        assertThat(response.ok).isTrue
        assertThat(response.lockAcquired).isFalse
        assertThat(lock.acquiredKeys).isEmpty()
    }

    @Test
    fun `OUTBOX replay does not touch the distributed lock`() {
        repo.put(sampleMessage("ob-1", DlqSource.OUTBOX, productId = null, orderId = 10L))
        val response = service.replay("ob-1", "alice", "k")
        assertThat(response.ok).isTrue
        assertThat(response.lockAcquired).isFalse
    }

    @Test
    fun `RESERVE_FAILED replay without productId returns MISSING_PRODUCT_ID`() {
        repo.put(sampleMessage("m-3", DlqSource.RESERVE_FAILED, productId = null, orderId = 11L))
        val response = service.replay("m-3", "alice", "k")
        assertThat(response.ok).isFalse
        assertThat(response.reason).isEqualTo("MISSING_PRODUCT_ID")
    }

    @Test
    fun `single replay is idempotent — same key returns same response`() {
        repo.put(sampleMessage("m-4", DlqSource.RESERVE_FAILED, productId = 1L, orderId = 1L))
        val a = service.replay("m-4", "alice", "same-key")
        val b = service.replay("m-4", "alice", "same-key")
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `bulk-replay defaults to dry-run when confirm=false`() {
        seedMessages(8)
        val response = service.bulkReplay(
            DlqBulkRequest(source = DlqSource.RESERVE_FAILED, confirm = false, reason = "test"),
            actor = "alice",
        )
        assertThat(response.dryRun).isTrue
        assertThat(response.attempted).isZero
        assertThat(audit.events.single().action).isEqualTo("DLQ_BULK_REPLAY_DRYRUN")
    }

    @Test
    fun `bulk-discard requires non-blank reason`() {
        assertThatThrownBy {
            service.bulkDiscard(
                DlqBulkRequest(source = DlqSource.RESERVE_FAILED, confirm = true, reason = ""),
                actor = "alice",
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `stats exposes byProduct and bySku dimensions`() {
        val from = Instant.parse("2026-05-17T00:00:00Z")
        val to = Instant.parse("2026-05-17T02:00:00Z")
        repo.put(sampleMessage("a", DlqSource.RESERVE_FAILED, productId = 100L, orderId = 1L, sku = "SKU-A", at = from.plusSeconds(10)))
        repo.put(sampleMessage("b", DlqSource.RESERVE_FAILED, productId = 100L, orderId = 2L, sku = "SKU-A", at = from.plusSeconds(20)))
        repo.put(sampleMessage("c", DlqSource.RELEASE_FAILED, productId = 200L, orderId = 3L, sku = "SKU-B", at = from.plusSeconds(30)))

        val s = service.stats(from, to, Duration.ofHours(1))

        assertThat(s.byProduct[100L]).isEqualTo(2)
        assertThat(s.byProduct[200L]).isEqualTo(1)
        assertThat(s.bySku["SKU-A"]).isEqualTo(2)
        assertThat(s.bySku["SKU-B"]).isEqualTo(1)
        assertThat(s.bySource).containsKeys(DlqSource.RESERVE_FAILED, DlqSource.RELEASE_FAILED)
    }

    private fun seedMessages(n: Int) {
        val sources = DlqSource.values()
        for (i in 0 until n) {
            val src = sources[i % sources.size]
            repo.put(
                sampleMessage(
                    id = "m-$i", source = src,
                    productId = (i + 1).toLong(), orderId = ((i % 5) + 1).toLong(),
                    at = Instant.parse("2026-05-17T00:00:00Z").plusSeconds((i * 30).toLong()),
                )
            )
        }
    }

    private fun sampleMessage(
        id: String,
        source: DlqSource,
        productId: Long?,
        orderId: Long?,
        sku: String? = null,
        at: Instant = Instant.parse("2026-05-17T01:00:00Z"),
    ) = DlqMessage(
        messageId = id,
        source = source,
        topic = when (source) {
            DlqSource.RESERVE_FAILED -> "inventory.reserve.dlq"
            DlqSource.RELEASE_FAILED -> "inventory.release.dlq"
            DlqSource.KAFKA_CONSUME -> "inventory.kafka.dlq"
            DlqSource.OUTBOX -> "internal:outbox"
        },
        productId = productId,
        sku = sku,
        orderId = orderId,
        errorType = "LOCK_TIMEOUT",
        errorMessage = "lock timeout 3000ms",
        payload = """{"productId":$productId}""",
        headers = emptyMap(),
        firstFailedAt = at,
        lastFailedAt = at,
        attempts = 1,
    )

    private class CapturingAudit : DlqAuditLog {
        val events: MutableList<DlqAuditLog.AuditEvent> = CopyOnWriteArrayList()
        override fun log(event: DlqAuditLog.AuditEvent) { events.add(event) }
    }

    /** Fake [DlqDistributedLock] — 테스트에서 락 동작을 제어. */
    private class FakeLock : DlqDistributedLock {
        var alwaysTimeout = false
        val acquiredKeys: MutableList<String> = mutableListOf()

        override fun <T> withLock(key: String, action: () -> T): DlqDistributedLock.LockResult<T> {
            if (alwaysTimeout) return DlqDistributedLock.LockResult.Timeout
            acquiredKeys.add(key)
            return DlqDistributedLock.LockResult.Acquired(action())
        }
    }
}
