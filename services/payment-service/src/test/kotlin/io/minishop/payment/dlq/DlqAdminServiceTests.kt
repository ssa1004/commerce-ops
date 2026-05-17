package io.minishop.payment.dlq

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

class DlqAdminServiceTests {

    private lateinit var repo: InMemoryDlqMessageRepository
    private lateinit var useCase: PaymentDlqUseCase
    private lateinit var jobs: InMemoryDlqBulkJobRepository
    private lateinit var bulk: DlqBulkJobService
    private lateinit var audit: CapturingAudit
    private lateinit var service: DlqAdminService

    @BeforeEach
    fun setUp() {
        repo = InMemoryDlqMessageRepository()
        useCase = PaymentDlqUseCase(repo)
        jobs = InMemoryDlqBulkJobRepository()
        bulk = DlqBulkJobService(jobs)
        audit = CapturingAudit()
        service = DlqAdminService(repo, useCase, audit, bulk)
    }

    @Test
    fun `replay copies the Idempotency-Key header to PG so the charge is not double-billed`() {
        repo.put(
            sampleMessage(
                "m-1", DlqSource.PAYMENT_CHARGE, paymentId = 1L, customerId = 7L,
                headers = mapOf("Idempotency-Key" to "pg-key-1234"),
            )
        )

        val response = service.replay("m-1", "alice", "admin-key-x")

        assertThat(response.ok).isTrue
        assertThat(response.idempotencyKey).isEqualTo("pg-key-1234")
        val e = audit.events.single()
        assertThat(e.action).isEqualTo("DLQ_REPLAY")
        assertThat(e.paymentId).isEqualTo(1L)
        assertThat(e.customerId).isEqualTo(7L)
        assertThat(e.extra["pgIdempotencyKey"]).isEqualTo("pg-key-1234")
    }

    @Test
    fun `replay synthesizes a deterministic key when original headers had none`() {
        repo.put(sampleMessage("m-2", DlqSource.PAYMENT_CHARGE, paymentId = 2L, customerId = 8L))
        val r = service.replay("m-2", "alice", "k")
        assertThat(r.idempotencyKey).isEqualTo("msg:m-2")
    }

    @Test
    fun `replay for PG_CALLBACK returns null idempotencyKey (callback re-runs full verification)`() {
        repo.put(sampleMessage("cb-1", DlqSource.PG_CALLBACK, paymentId = 3L, customerId = 9L))
        val r = service.replay("cb-1", "alice", "k")
        assertThat(r.idempotencyKey).isNull()
        assertThat(r.ok).isTrue
    }

    @Test
    fun `PAYMENT_REFUND actions are tagged risk=high in audit log`() {
        repo.put(sampleMessage("rf-1", DlqSource.PAYMENT_REFUND, paymentId = 4L, customerId = 10L))
        service.discard("rf-1", "bob", "duplicate refund event")
        val e = audit.events.single()
        assertThat(e.action).isEqualTo("DLQ_DISCARD")
        assertThat(e.extra["risk"]).isEqualTo("high")
    }

    @Test
    fun `single replay is idempotent — same admin key returns same response`() {
        repo.put(sampleMessage("m-3", DlqSource.PAYMENT_CHARGE, paymentId = 5L, customerId = 7L))
        val a = service.replay("m-3", "alice", "same-key")
        val b = service.replay("m-3", "alice", "same-key")
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `bulk-replay defaults to dry-run when confirm=false`() {
        seedMessages(8)
        val response = service.bulkReplay(
            DlqBulkRequest(source = DlqSource.PAYMENT_CHARGE, confirm = false, reason = "test"),
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
                DlqBulkRequest(source = DlqSource.PAYMENT_CHARGE, confirm = true, reason = " "),
                actor = "alice",
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `stats exposes byCustomer dimension`() {
        val from = Instant.parse("2026-05-17T00:00:00Z")
        val to = Instant.parse("2026-05-17T02:00:00Z")
        repo.put(sampleMessage("a", DlqSource.PAYMENT_CHARGE, paymentId = 1L, customerId = 7L, at = from.plusSeconds(10)))
        repo.put(sampleMessage("b", DlqSource.PAYMENT_CHARGE, paymentId = 2L, customerId = 7L, at = from.plusSeconds(20)))
        repo.put(sampleMessage("c", DlqSource.PAYMENT_REFUND, paymentId = 3L, customerId = 8L, at = from.plusSeconds(30)))

        val s = service.stats(from, to, Duration.ofHours(1))

        assertThat(s.byCustomer[7L]).isEqualTo(2)
        assertThat(s.byCustomer[8L]).isEqualTo(1)
        assertThat(s.bySource).containsKeys(DlqSource.PAYMENT_CHARGE, DlqSource.PAYMENT_REFUND)
    }

    private fun seedMessages(n: Int) {
        val sources = DlqSource.values()
        for (i in 0 until n) {
            val src = sources[i % sources.size]
            repo.put(
                sampleMessage(
                    id = "m-$i",
                    source = src,
                    paymentId = (i + 1).toLong(),
                    customerId = ((i % 5) + 1).toLong(),
                    at = Instant.parse("2026-05-17T00:00:00Z").plusSeconds((i * 30).toLong()),
                )
            )
        }
    }

    private fun sampleMessage(
        id: String,
        source: DlqSource,
        paymentId: Long?,
        customerId: Long?,
        headers: Map<String, String> = emptyMap(),
        at: Instant = Instant.parse("2026-05-17T01:00:00Z"),
    ) = DlqMessage(
        messageId = id,
        source = source,
        topic = when (source) {
            DlqSource.PAYMENT_CHARGE -> "payment.charge.dlq"
            DlqSource.PAYMENT_REFUND -> "payment.refund.dlq"
            DlqSource.PG_CALLBACK -> "pg.callback.dlq"
            DlqSource.OUTBOX -> "internal:outbox"
        },
        paymentId = paymentId,
        customerId = customerId,
        errorType = "PG_TIMEOUT",
        errorMessage = "timeout after 5000ms",
        payload = """{"id":$paymentId}""",
        headers = headers,
        firstFailedAt = at,
        lastFailedAt = at,
        attempts = 1,
    )

    private class CapturingAudit : DlqAuditLog {
        val events: MutableList<DlqAuditLog.AuditEvent> = CopyOnWriteArrayList()
        override fun log(event: DlqAuditLog.AuditEvent) { events.add(event) }
    }
}
