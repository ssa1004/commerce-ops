package io.minishop.inventory.web.dto

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull

/**
 * reserve 호출의 *모양 cap*. order-service 의 CreateOrderItemRequest 와 동일 상한 (1 ~ 1000) 을
 * inventory 측에서도 한 번 더 강제 — order-service 를 우회해 inventory 를 직접 두드리는 경로 (예:
 * 통합 테스트, 내부 도구) 에서도 같은 보호.
 *
 * OWASP API4 (Unrestricted Resource Consumption) — 비정상적으로 큰 quantity 가 한 productId 의
 * 가용 재고를 단번에 0 으로 떨어뜨리는 시나리오 차단. 비즈니스의 *진짜* 한도는 catalog 에 있어야
 * 하지만, 모양 단계의 sanity 가드도 함께 둔다.
 */
@JvmRecord
data class ReserveRequest(
    @field:NotNull val productId: Long?,
    @field:NotNull val orderId: Long?,
    @field:NotNull @field:Min(1) @field:Max(1000) val quantity: Int?,
)
