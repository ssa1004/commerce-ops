package io.minishop.inventory.dlq

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class InMemoryDlqMessageRepositoryTests {

    @Test
    fun `list orders by lastFailedAt desc and paginates`() {
        val repo = InMemoryDlqMessageRepository()
        val base = Instant.parse("2026-05-17T00:00:00Z")
        (0 until 5).forEach { i -> repo.put(msg("m-$i", at = base.plusSeconds((i * 10).toLong()))) }
        val p1 = repo.list(DlqListQuery(size = 2))
        assertThat(p1.items.map { it.messageId }).containsExactly("m-4", "m-3")
        val p2 = repo.list(DlqListQuery(size = 2, cursor = p1.nextCursor))
        assertThat(p2.items.map { it.messageId }).containsExactly("m-2", "m-1")
    }

    @Test
    fun `match never crosses source boundaries`() {
        val repo = InMemoryDlqMessageRepository()
        repo.put(msg("a", source = DlqSource.RESERVE_FAILED))
        repo.put(msg("b", source = DlqSource.RELEASE_FAILED))
        repo.put(msg("c", source = DlqSource.RESERVE_FAILED))
        val matched = repo.match(DlqBulkFilter(source = DlqSource.RESERVE_FAILED))
        assertThat(matched).containsExactlyInAnyOrder("a", "c")
    }

    @Test
    fun `stats with sku dimension counts each variant separately`() {
        val repo = InMemoryDlqMessageRepository()
        val from = Instant.parse("2026-05-17T00:00:00Z")
        val to = from.plus(Duration.ofHours(2))
        repo.put(msg("a", sku = "S-RED", at = from.plus(Duration.ofMinutes(10))))
        repo.put(msg("b", sku = "S-RED", at = from.plus(Duration.ofMinutes(70))))
        repo.put(msg("c", sku = "S-BLUE", at = from.plus(Duration.ofMinutes(110))))

        val s = repo.stats(from, to, Duration.ofHours(1))

        assertThat(s.bySku["S-RED"]).isEqualTo(2)
        assertThat(s.bySku["S-BLUE"]).isEqualTo(1)
        assertThat(s.series).extracting("bucketStart").isSorted
    }

    private fun msg(
        id: String,
        source: DlqSource = DlqSource.RESERVE_FAILED,
        productId: Long? = 1L,
        sku: String? = null,
        orderId: Long? = 1L,
        at: Instant = Instant.parse("2026-05-17T00:00:00Z"),
    ) = DlqMessage(
        messageId = id,
        source = source,
        topic = "test",
        productId = productId,
        sku = sku,
        orderId = orderId,
        errorType = "X",
        errorMessage = "msg",
        payload = "{}",
        headers = emptyMap(),
        firstFailedAt = at,
        lastFailedAt = at,
        attempts = 1,
    )
}
