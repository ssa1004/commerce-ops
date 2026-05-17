package io.minishop.inventory.dlq

import java.time.Instant

/**
 * inventory-service 의 DLQ 한 건. order/payment 와 *같은 모양* 의 표준 + service 특유 필드:
 *
 * - [productId] — 1 차 차원. stats 의 `byProduct` 가 이 키로 묶인다.
 * - [sku] — 동일 product 의 외부 식별자 (변종 / 색상 SKU 등). 콘솔이 product 보다 SKU 단위로
 *   보고 싶을 때를 위해 별도 키. 본 service 의 현재 도메인은 productId 만 있고 SKU 는 미사용 —
 *   필드는 미래 확장 (운영 콘솔이 SKU 차원으로 필터링하는 케이스).
 * - [orderId] — 어느 주문의 reserve/release 에서 격리됐는지. 사고 추적의 cross-reference.
 *
 * inventory 특유 — replay 시 **Redisson 분산락 재획득** 이 핵심. 락 없이 재처리하면 동시 reserve
 * 가 일어나 재고가 음수가 되는 동시성 사고.
 */
data class DlqMessage(
    val messageId: String,
    val source: DlqSource,
    val topic: String,
    val productId: Long?,
    val sku: String?,
    val orderId: Long?,
    val errorType: String,
    val errorMessage: String,
    val payload: String,
    val headers: Map<String, String>,
    val firstFailedAt: Instant,
    val lastFailedAt: Instant,
    val attempts: Int,
)
