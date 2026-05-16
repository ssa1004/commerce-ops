package io.minishop.order.web

import io.minishop.order.service.OrderService
import io.minishop.order.web.dto.CreateOrderRequest
import io.minishop.order.web.dto.OrderResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.util.UriComponentsBuilder

@RestController
@RequestMapping("/orders")
@Validated
class OrderController(
    private val orderService: OrderService,
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody request: CreateOrderRequest): ResponseEntity<OrderResponse> {
        val order = orderService.create(request)
        val body = OrderResponse.from(order)
        return ResponseEntity
            .created(UriComponentsBuilder.fromPath("/orders/{id}").buildAndExpand(order.id).toUri())
            .body(body)
    }

    @GetMapping("/{id}")
    fun getById(@PathVariable id: Long): OrderResponse =
        OrderResponse.from(orderService.getById(id))

    /**
     * 최근 주문 목록. 의도적으로 *naive*하게 구현 — items는 lazy fetch이므로
     * 응답 직렬화에서 order당 SELECT가 한 번씩 더 나간다 (N+1).
     * `slow-query-detector` 모듈이 이걸 자동으로 감지하는 데모 경로.
     */
    @GetMapping
    fun listRecent(
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) size: Int,
    ): List<OrderResponse> =
        orderService.listRecent(size).map { OrderResponse.from(it) }
}
