package io.minishop.order.dlq

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.stereotype.Repository
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * DLQ 메시지의 *기본* 저장소 — in-memory.
 *
 * 본 step 의 목적은 *관리 콘솔 API 표준화*. 실제 데이터의 원천은 source 별로 다르다
 * (Kafka DLQ topic / outbox FAILED rows / saga error log) 라 source-specific 어댑터에서
 * 본 저장소로 *동기화* 하거나, 별도 어댑터로 직접 구현하는 방식이 다음 step.
 *
 * 본 step 에서는 어떤 service 가 들어와도 API 가 같은 모양으로 응답하는 것이 우선. test 에서도
 * 같은 저장소를 채워 list / get / replay / discard 흐름을 한 곳에서 검증할 수 있다.
 *
 * cursor 는 *idx-base64* — 의도적으로 단순. 운영에서는 source 별 백엔드의 native cursor
 * (Kafka offset, DB primary key) 를 그대로 base64.
 */
@Repository
@ConditionalOnMissingBean(name = ["dlqMessageRepository"])
class InMemoryDlqMessageRepository : DlqMessageRepository {

    private val byId = ConcurrentHashMap<String, DlqMessage>()

    /** 테스트 helper — adapter 가 자동 동기화하기 전까지의 seed. */
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
            byOrder = inRange.mapNotNull { it.orderId }.groupingBy { it }.eachCount().mapValues { it.value.toLong() },
            byCustomer = inRange.mapNotNull { it.customerId }.groupingBy { it }.eachCount().mapValues { it.value.toLong() },
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
        orderId = orderId,
        customerId = customerId,
        errorType = errorType,
        errorMessageShort = errorMessage.take(140),
        firstFailedAt = firstFailedAt,
        lastFailedAt = lastFailedAt,
        attempts = attempts,
    )
}
