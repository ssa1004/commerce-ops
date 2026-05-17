package io.minishop.order.dlq

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

class DlqAdminServiceTests {

    private lateinit var repo: InMemoryDlqMessageRepository
    private lateinit var useCase: OrderDlqUseCase
    private lateinit var jobs: InMemoryDlqBulkJobRepository
    private lateinit var bulk: DlqBulkJobService
    private lateinit var audit: CapturingAudit
    private lateinit var service: DlqAdminService

    @BeforeEach
    fun setUp() {
        repo = InMemoryDlqMessageRepository()
        useCase = OrderDlqUseCase(repo)
        jobs = InMemoryDlqBulkJobRepository()
        bulk = DlqBulkJobService(jobs)
        audit = CapturingAudit()
        service = DlqAdminService(repo, useCase, audit, bulk)
    }

    @Test
    fun `list filters by source and paginates with cursor`() {
        // 20 건 — 매 5 번째가 ORDER_EVENT 라 ORDER_EVENT 는 4 건 (i=0,5,10,15).
        seedMessages(20)
        val first = service.list(DlqListQuery(source = DlqSource.ORDER_EVENT, size = 3))
        assertThat(first.items).hasSize(3)
        assertThat(first.items).allMatch { it.source == DlqSource.ORDER_EVENT }
        assertThat(first.nextCursor).isNotNull()

        val second = service.list(DlqListQuery(source = DlqSource.ORDER_EVENT, cursor = first.nextCursor, size = 3))
        assertThat(second.items).hasSize(1)            // ORDER_EVENT 4 건 - 3 = 1
        assertThat(second.nextCursor).isNull()
    }

    @Test
    fun `single replay records audit log with messageId and source`() {
        val msg = sampleMessage("m-1", DlqSource.ORDER_EVENT, orderId = 1001L, customerId = 7L)
        repo.put(msg)

        val response = service.replay("m-1", actor = "alice", idempotencyKey = "k-1")

        assertThat(response.ok).isTrue
        assertThat(audit.events).hasSize(1)
        val e = audit.events.first()
        assertThat(e.action).isEqualTo("DLQ_REPLAY")
        assertThat(e.actor).isEqualTo("alice")
        assertThat(e.source).isEqualTo(DlqSource.ORDER_EVENT)
        assertThat(e.messageId).isEqualTo("m-1")
        assertThat(e.orderId).isEqualTo(1001L)
        assertThat(e.customerId).isEqualTo(7L)
    }

    @Test
    fun `single replay is idempotent — same key returns same result instance contents`() {
        repo.put(sampleMessage("m-2", DlqSource.ORDER_EVENT, orderId = 2L, customerId = 7L))

        val a = service.replay("m-2", "alice", "same-key")
        val b = service.replay("m-2", "alice", "same-key")

        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `inbox replay is treated as INBOX_DEDUP_SKIPPED (still ok=true)`() {
        repo.put(sampleMessage("inbox-1", DlqSource.INVENTORY_INBOX, orderId = 1L, customerId = 1L))
        val r = service.replay("inbox-1", "alice", "k")
        assertThat(r.ok).isTrue
        assertThat(r.reason).isEqualTo("INBOX_DEDUP_SKIPPED")
    }

    @Test
    fun `discard requires soft-delete via repository and records audit with reason`() {
        repo.put(sampleMessage("m-3", DlqSource.OUTBOX, orderId = 3L, customerId = 9L))

        val response = service.discard("m-3", "bob", reason = "duplicate event from chaos")

        assertThat(response.ok).isTrue
        assertThat(repo.find("m-3")).isNull()
        val e = audit.events.single()
        assertThat(e.action).isEqualTo("DLQ_DISCARD")
        assertThat(e.reason).isEqualTo("duplicate event from chaos")
    }

    @Test
    fun `bulk-replay defaults to dry-run when confirm=false`() {
        seedMessages(6)

        val response = service.bulkReplay(
            DlqBulkRequest(source = DlqSource.ORDER_EVENT, confirm = false, reason = "test"),
            actor = "alice",
        )

        assertThat(response.dryRun).isTrue
        assertThat(response.totalMatched).isPositive
        assertThat(response.attempted).isZero
        assertThat(audit.events.single().action).isEqualTo("DLQ_BULK_REPLAY_DRYRUN")
    }

    @Test
    fun `bulk-discard rejects blank reason`() {
        seedMessages(6)
        assertThatThrownBy {
            service.bulkDiscard(
                DlqBulkRequest(source = DlqSource.ORDER_EVENT, confirm = true, reason = "   "),
                actor = "alice",
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("bulk-discard requires non-blank reason")
    }

    @Test
    fun `bulk-replay actually executes when confirm=true and reports per-message outcome`() {
        seedMessages(4) // ORDER_EVENT 가 2 건 (idx 0,3)

        val response = service.bulkReplay(
            DlqBulkRequest(source = DlqSource.ORDER_EVENT, confirm = true, reason = "rerun"),
            actor = "alice",
        )

        assertThat(response.dryRun).isFalse
        assertThat(response.attempted).isEqualTo(response.totalMatched)
        assertThat(response.succeeded + response.failed).isEqualTo(response.attempted)
        assertThat(response.status).isIn(BulkJobStatus.SUCCEEDED, BulkJobStatus.PARTIAL)
    }

    @Test
    fun `stats validates range and bucket`() {
        val now = Instant.parse("2026-05-17T10:00:00Z")
        assertThatThrownBy { service.stats(now, now.minusSeconds(1), Duration.ofMinutes(1)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { service.stats(now.minusSeconds(60), now, Duration.ofSeconds(30)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `stats returns byOrder and byCustomer dimensions for order-service`() {
        val from = Instant.parse("2026-05-17T00:00:00Z")
        val to = Instant.parse("2026-05-17T02:00:00Z")
        repo.put(sampleMessage("a", DlqSource.ORDER_EVENT, orderId = 1L, customerId = 7L, at = from.plusSeconds(10)))
        repo.put(sampleMessage("b", DlqSource.OUTBOX, orderId = 1L, customerId = 8L, at = from.plusSeconds(20)))
        repo.put(sampleMessage("c", DlqSource.SAGA, orderId = 2L, customerId = 7L, at = from.plusSeconds(30)))

        val s = service.stats(from, to, Duration.ofHours(1))

        assertThat(s.totalMessages).isEqualTo(3)
        assertThat(s.byOrder[1L]).isEqualTo(2)
        assertThat(s.byOrder[2L]).isEqualTo(1)
        assertThat(s.byCustomer[7L]).isEqualTo(2)
        assertThat(s.byCustomer[8L]).isEqualTo(1)
        assertThat(s.bySource).containsKeys(DlqSource.ORDER_EVENT, DlqSource.OUTBOX, DlqSource.SAGA)
    }

    private fun seedMessages(n: Int) {
        val sources = DlqSource.values()
        for (i in 0 until n) {
            val src = sources[i % sources.size]
            repo.put(
                sampleMessage(
                    id = "m-$i",
                    source = src,
                    orderId = (i + 1).toLong(),
                    customerId = ((i % 5) + 1).toLong(),
                    at = Instant.parse("2026-05-17T00:00:00Z").plusSeconds((i * 30).toLong()),
                )
            )
        }
    }

    private fun sampleMessage(
        id: String,
        source: DlqSource,
        orderId: Long?,
        customerId: Long?,
        at: Instant = Instant.parse("2026-05-17T01:00:00Z"),
    ) = DlqMessage(
        messageId = id,
        source = source,
        topic = when (source) {
            DlqSource.ORDER_EVENT -> "order.events.dlq"
            DlqSource.INVENTORY_INBOX -> "inventory.events.dlq"
            DlqSource.PAYMENT_INBOX -> "payment.events.dlq"
            DlqSource.SAGA -> "internal:saga"
            DlqSource.OUTBOX -> "internal:outbox"
        },
        orderId = orderId,
        customerId = customerId,
        errorType = "KAFKA_SEND_TIMEOUT",
        errorMessage = "timeout after 5000ms",
        payload = """{"id":$orderId}""",
        headers = mapOf("traceparent" to "00-aaa-bbb-01"),
        firstFailedAt = at,
        lastFailedAt = at,
        attempts = 1,
    )

    private class CapturingAudit : DlqAuditLog {
        val events: MutableList<DlqAuditLog.AuditEvent> = CopyOnWriteArrayList()
        override fun log(event: DlqAuditLog.AuditEvent) { events.add(event) }
    }
}
