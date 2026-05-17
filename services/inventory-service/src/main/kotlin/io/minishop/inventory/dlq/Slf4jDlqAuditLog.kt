package io.minishop.inventory.dlq

import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.stereotype.Component

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
            event.productId?.let { mdcPut(mdcKeys, "audit.productId", it.toString()) }
            event.sku?.let { mdcPut(mdcKeys, "audit.sku", it) }
            event.orderId?.let { mdcPut(mdcKeys, "audit.orderId", it.toString()) }
            event.reason?.let { mdcPut(mdcKeys, "audit.reason", truncate(it)) }
            mdcPut(mdcKeys, "audit.result", event.result)
            event.extra.forEach { (k, v) -> mdcPut(mdcKeys, "audit.$k", truncate(v)) }
            log.info(
                "DLQ_AUDIT action={} actor={} source={} messageId={} productId={} sku={} orderId={} result={}",
                event.action,
                event.actor,
                event.source,
                event.messageId,
                event.productId,
                event.sku,
                event.orderId,
                event.result,
            )
        } finally {
            mdcKeys.forEach { MDC.remove(it) }
        }
    }

    private fun mdcPut(keys: MutableList<String>, k: String, v: String) {
        MDC.put(k, v); keys.add(k)
    }

    private fun truncate(s: String): String = if (s.length <= 500) s else s.take(500) + "...(truncated)"
}
