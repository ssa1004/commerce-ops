package io.minishop.order.dlq

import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.stereotype.Component

/**
 * audit 로그 default 구현 — Slf4j logger. Loki 로 흘러간 다음에 grep 가능한 *구조화 메시지*.
 *
 * MDC 에 audit/action/actor/source/messageId/orderId/customerId 를 넣고 한 줄로 출력 —
 * Loki 에서 `{action="DLQ_REPLAY"} | json` 같은 쿼리로 한 번에 추출.
 *
 * 별도 audit DB 백엔드가 필요해지면 [DlqAuditLog] 를 구현한 bean 을 등록해 본 구현을 대체.
 */
@Component
class Slf4jDlqAuditLog : DlqAuditLog {

    private val log = LoggerFactory.getLogger("AUDIT.DLQ")

    override fun log(event: DlqAuditLog.AuditEvent) {
        val mdcKeys = mutableListOf<String>()
        try {
            mdcPut(mdcKeys, "audit", "true")
            mdcPut(mdcKeys, "audit.action", event.action)
            mdcPut(mdcKeys, "audit.actor", event.actor)
            event.source?.let { mdcPut(mdcKeys, "audit.source", it.name) }
            event.messageId?.let { mdcPut(mdcKeys, "audit.messageId", it) }
            event.orderId?.let { mdcPut(mdcKeys, "audit.orderId", it.toString()) }
            event.customerId?.let { mdcPut(mdcKeys, "audit.customerId", it.toString()) }
            event.reason?.let { mdcPut(mdcKeys, "audit.reason", truncate(it)) }
            mdcPut(mdcKeys, "audit.result", event.result)
            event.extra.forEach { (k, v) -> mdcPut(mdcKeys, "audit.$k", truncate(v)) }
            log.info(
                "DLQ_AUDIT action={} actor={} source={} messageId={} orderId={} customerId={} result={}",
                event.action,
                event.actor,
                event.source,
                event.messageId,
                event.orderId,
                event.customerId,
                event.result,
            )
        } finally {
            mdcKeys.forEach { MDC.remove(it) }
        }
    }

    private fun mdcPut(keys: MutableList<String>, k: String, v: String) {
        MDC.put(k, v); keys.add(k)
    }

    /** Loki 의 field 길이 한도와 디버그 가독성의 균형. 500 자 컷오프 + 표식. */
    private fun truncate(s: String): String = if (s.length <= 500) s else s.take(500) + "...(truncated)"
}
