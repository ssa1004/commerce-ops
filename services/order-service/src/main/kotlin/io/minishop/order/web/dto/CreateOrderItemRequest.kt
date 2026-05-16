package io.minishop.order.web.dto

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.PositiveOrZero
import java.math.BigDecimal

/**
 * 단일 item 의 수량 / 금액 상한:
 * - `quantity`: 일반 e-commerce 한 SKU 의 합리 상한. 1 ~ 1000 범위로 둬 inventory.reserve
 *   호출이 비정상적으로 큰 수량을 단번에 잡아 다른 주문의 가용 재고를 0 으로 만드는 시나리오를
 *   모양 단계에서 차단 (OWASP API4 보조 cap — 비즈니스 정책의 자리 잡기).
 * - 가격은 응답으로 다시 echo 되긴 하지만 catalog 의 진실은 catalog 서비스가 갖는 미래 형태이므로
 *   클라이언트 입력값 그대로 사용 — 도메인 차원의 cap (예: 1억) 도 함께 둘 수 있으나 본 변경
 *   범위는 *fan-out / DoS* 측면만.
 */
@JvmRecord
data class CreateOrderItemRequest(
    @field:NotNull val productId: Long,
    @field:NotNull @field:Min(1) @field:Max(1000) val quantity: Int,
    @field:NotNull @field:PositiveOrZero val price: BigDecimal,
)
