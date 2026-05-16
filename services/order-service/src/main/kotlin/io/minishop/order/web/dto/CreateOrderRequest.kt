package io.minishop.order.web.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

/**
 * 입력 cap 의도:
 * - `items` 의 길이 상한을 명시 — 한 주문에 수천 개의 item 을 실어 보내면 reserve 호출이
 *   N 번 연쇄로 일어나며 inventory / payment / adaptive limiter 한도를 사실상 한 주문이 다
 *   먹어버린다. OWASP API4 (Unrestricted Resource Consumption) 차단. 일반적인 e-commerce 장바구니
 *   크기로 50 은 충분히 크면서, 한 호출이 만들 수 있는 fan-out 의 상한을 명시적으로 둔다.
 *
 * 권한/소유권 검증 (userId 가 호출자 자신의 id 와 일치하는지 — BOLA 방어) 은 인증 게이트웨이를
 * 도입하는 ROADMAP 항목. 본 record 의 validation 은 *모양* 의 안전만 보장한다.
 */
@JvmRecord
data class CreateOrderRequest(
    @field:NotNull val userId: Long,
    @field:NotEmpty @field:Size(max = 50) @field:Valid val items: List<CreateOrderItemRequest>,
)
