package io.minishop.inventory.dlq

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.stereotype.Repository
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * inventory-service DLQ 메시지의 기본 저장소 — in-memory. ADR-026 의 표준 모양.
 *
 * 실제 source-specific 어댑터 (RESERVE_FAILED rows / outbox FAILED rows / kafka admin) wiring 은
 * 후속 step. 현재는 API 응답이 service 들 사이에 *같은 모양* 임을 보장하는 것이 우선.
 */
@Repository
@ConditionalOnMissingBean(name = ["dlqMessageRepository"])
class InMemoryDlqMessageRepository : DlqMessageRepository {

    private val byId = ConcurrentHashMap<String, DlqMessage>()

    fun put(message: DlqMessage) {
        byId[message.messageId] = message
    }

    fun clear() = byId.clear()

    override fun list(query: DlqListQuery): DlqPageResponse {
        val filtered = byId.values
            .asSequence()
            .filter { query.source == null || it.source == query.source }
            .filter { query.topic.isNullOrBlank() || it.topic == query.topic }
            .filter { query.errorType.isNullOrBlank() || it.errorType == query.errorType }
            .filter { query.from == null || !it.lastFailedAt.isBefore(query.from) }
            .filter { query.to == null || !it.lastFailedAt.isAfter(query.to) }
            .sortedByDescending { it.lastFailedAt }
            .toList()
        val total = filtered.size.toLong()
        val cursorIdx = decodeCursor(query.cursor)
        val size = query.size.coerceIn(1, 100)
        val page = filtered.drop(cursorIdx).take(size)
        val next = if (cursorIdx + size < filtered.size) encodeCursor(cursorIdx + size) else null
        return DlqPageResponse(
            items = page.map { it.toListItem() },
            nextCursor = next,
            totalEstimate = total,
        )
    }

    override fun find(messageId: String): DlqMessage? = byId[messageId]

    override fun delete(messageId: String): Boolean = byId.remove(messageId) != null

    override fun stats(from: Instant, to: Instant, bucket: Duration): DlqStatsResponse {
        val inRange = byId.values.filter {
            !it.lastFailedAt.isBefore(from) && !it.lastFailedAt.isAfter(to)
        }
        val series = mutableMapOf<Instant, Long>()
        var t = from
        while (t.isBefore(to) || t == to) {
            series[t] = 0
            t = t.plus(bucket)
        }
        for (m in inRange) {
            val bucketStart = bucketFloor(m.lastFailedAt, from, bucket)
            series[bucketStart] = (series[bucketStart] ?: 0) + 1
        }
        return DlqStatsResponse(
            from = from,
            to = to,
            bucket = bucket.toString(),
            series = series.toSortedMap().map { (k, v) -> DlqStatsBucket(k, v) },
            byErrorType = inRange.groupingBy { it.errorType }.eachCount().mapValues { it.value.toLong() },
            bySource = inRange.groupingBy { it.source }.eachCount().mapValues { it.value.toLong() },
            byProduct = inRange.mapNotNull { it.productId }.groupingBy { it }.eachCount().mapValues { it.value.toLong() },
            bySku = inRange.mapNotNull { it.sku }.groupingBy { it }.eachCount().mapValues { it.value.toLong() },
            totalMessages = inRange.size.toLong(),
        )
    }

    override fun match(filter: DlqBulkFilter): List<String> {
        return byId.values
            .asSequence()
            .filter { it.source == filter.source }
            .filter { filter.from == null || !it.lastFailedAt.isBefore(filter.from) }
            .filter { filter.to == null || !it.lastFailedAt.isAfter(filter.to) }
            .filter { filter.errorType.isNullOrBlank() || it.errorType == filter.errorType }
            .sortedBy { it.firstFailedAt }
            .take(filter.maxMessages)
            .map { it.messageId }
            .toList()
    }

    private fun bucketFloor(at: Instant, from: Instant, bucket: Duration): Instant {
        val elapsed = Duration.between(from, at).toMillis()
        val step = bucket.toMillis()
        if (step <= 0) return from
        val k = elapsed / step
        return from.plusMillis(k * step)
    }

    private fun encodeCursor(idx: Int): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(idx.toString().toByteArray())

    private fun decodeCursor(cursor: String?): Int {
        if (cursor.isNullOrBlank()) return 0
        return try {
            String(Base64.getUrlDecoder().decode(cursor)).toInt().coerceAtLeast(0)
        } catch (_: Exception) {
            0
        }
    }

    private fun DlqMessage.toListItem() = DlqListItemResponse(
        messageId = messageId,
        source = source,
        topic = topic,
        productId = productId,
        sku = sku,
        orderId = orderId,
        errorType = errorType,
        errorMessageShort = errorMessage.take(140),
        firstFailedAt = firstFailedAt,
        lastFailedAt = lastFailedAt,
        attempts = attempts,
    )
}
