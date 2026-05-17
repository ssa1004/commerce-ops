package io.minishop.payment.dlq

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class InMemoryDlqMessageRepositoryTests {

    @Test
    fun `list orders by lastFailedAt desc and paginates via cursor`() {
        val repo = InMemoryDlqMessageRepository()
        val base = Instant.parse("2026-05-17T00:00:00Z")
        (0 until 5).forEach { i ->
            repo.put(msg("m-$i", at = base.plusSeconds((i * 10).toLong())))
        }
        val p1 = repo.list(DlqListQuery(size = 2))
        assertThat(p1.items.map { it.messageId }).containsExactly("m-4", "m-3")
        val p2 = repo.list(DlqListQuery(size = 2, cursor = p1.nextCursor))
        assertThat(p2.items.map { it.messageId }).containsExactly("m-2", "m-1")
    }

    @Test
    fun `match scoped to single source - never cross source`() {
        val repo = InMemoryDlqMessageRepository()
        repo.put(msg("a", source = DlqSource.PAYMENT_CHARGE))
        repo.put(msg("b", source = DlqSource.PAYMENT_REFUND))
        repo.put(msg("c", source = DlqSource.PAYMENT_CHARGE))
        val matched = repo.match(DlqBulkFilter(source = DlqSource.PAYMENT_CHARGE))
        assertThat(matched).containsExactlyInAnyOrder("a", "c")
    }

    @Test
    fun `stats series sorted asc + byCustomer dimension`() {
        val repo = InMemoryDlqMessageRepository()
        val from = Instant.parse("2026-05-17T00:00:00Z")
        val to = from.plus(Duration.ofHours(2))
        repo.put(msg("a", customerId = 7L, at = from.plus(Duration.ofMinutes(10))))
        repo.put(msg("b", customerId = 7L, at = from.plus(Duration.ofMinutes(70))))
        repo.put(msg("c", customerId = 8L, at = from.plus(Duration.ofMinutes(110))))

        val s = repo.stats(from, to, Duration.ofHours(1))

        assertThat(s.series).extracting("bucketStart").isSorted
        assertThat(s.byCustomer[7L]).isEqualTo(2)
        assertThat(s.byCustomer[8L]).isEqualTo(1)
    }

    private fun msg(
        id: String,
        source: DlqSource = DlqSource.PAYMENT_CHARGE,
        paymentId: Long? = 1L,
        customerId: Long? = 1L,
        at: Instant = Instant.parse("2026-05-17T00:00:00Z"),
    ) = DlqMessage(
        messageId = id,
        source = source,
        topic = "test",
        paymentId = paymentId,
        customerId = customerId,
        errorType = "X",
        errorMessage = "msg",
        payload = "{}",
        headers = emptyMap(),
        firstFailedAt = at,
        lastFailedAt = at,
        attempts = 1,
    )
}
