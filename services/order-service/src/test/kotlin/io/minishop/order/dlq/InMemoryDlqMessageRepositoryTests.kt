package io.minishop.order.dlq

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class InMemoryDlqMessageRepositoryTests {

    @Test
    fun `list orders by lastFailedAt desc and paginates with stable cursor`() {
        val repo = InMemoryDlqMessageRepository()
        val base = Instant.parse("2026-05-17T00:00:00Z")
        (0 until 7).forEach { i ->
            repo.put(msg("m-$i", at = base.plusSeconds((i * 10).toLong())))
        }

        val p1 = repo.list(DlqListQuery(size = 3))
        assertThat(p1.items.map { it.messageId }).containsExactly("m-6", "m-5", "m-4")

        val p2 = repo.list(DlqListQuery(size = 3, cursor = p1.nextCursor))
        assertThat(p2.items.map { it.messageId }).containsExactly("m-3", "m-2", "m-1")

        val p3 = repo.list(DlqListQuery(size = 3, cursor = p2.nextCursor))
        assertThat(p3.items.map { it.messageId }).containsExactly("m-0")
        assertThat(p3.nextCursor).isNull()
    }

    @Test
    fun `match returns ids in firstFailedAt asc — replay re-runs oldest first`() {
        val repo = InMemoryDlqMessageRepository()
        val base = Instant.parse("2026-05-17T00:00:00Z")
        repo.put(msg("a", source = DlqSource.ORDER_EVENT, at = base.plusSeconds(40)))
        repo.put(msg("b", source = DlqSource.ORDER_EVENT, at = base.plusSeconds(10)))
        repo.put(msg("c", source = DlqSource.ORDER_EVENT, at = base.plusSeconds(20)))
        repo.put(msg("z", source = DlqSource.OUTBOX, at = base.plusSeconds(15)))

        val matched = repo.match(DlqBulkFilter(source = DlqSource.ORDER_EVENT))
        assertThat(matched).containsExactly("b", "c", "a")
    }

    @Test
    fun `stats buckets are sorted ascending and include byOrder dimension`() {
        val repo = InMemoryDlqMessageRepository()
        val from = Instant.parse("2026-05-17T00:00:00Z")
        val to = from.plus(Duration.ofHours(3))
        repo.put(msg("a", orderId = 100L, at = from.plus(Duration.ofMinutes(5))))
        repo.put(msg("b", orderId = 100L, at = from.plus(Duration.ofMinutes(70))))
        repo.put(msg("c", orderId = 200L, at = from.plus(Duration.ofMinutes(140))))

        val s = repo.stats(from, to, Duration.ofHours(1))

        assertThat(s.series).extracting("bucketStart").isSorted
        assertThat(s.byOrder[100L]).isEqualTo(2)
        assertThat(s.byOrder[200L]).isEqualTo(1)
        assertThat(s.totalMessages).isEqualTo(3)
    }

    @Test
    fun `delete returns false when messageId not found`() {
        val repo = InMemoryDlqMessageRepository()
        assertThat(repo.delete("nope")).isFalse()
    }

    private fun msg(
        id: String,
        source: DlqSource = DlqSource.ORDER_EVENT,
        orderId: Long? = 1L,
        customerId: Long? = 1L,
        at: Instant = Instant.parse("2026-05-17T00:00:00Z"),
    ) = DlqMessage(
        messageId = id,
        source = source,
        topic = "test",
        orderId = orderId,
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
