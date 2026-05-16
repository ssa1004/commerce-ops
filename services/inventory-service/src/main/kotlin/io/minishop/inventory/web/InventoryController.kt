package io.minishop.inventory.web

import io.minishop.inventory.service.InventoryService
import io.minishop.inventory.web.dto.InventoryResponse
import io.minishop.inventory.web.dto.ReleaseRequest
import io.minishop.inventory.web.dto.ReservationResponse
import io.minishop.inventory.web.dto.ReserveRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/inventories")
class InventoryController(
    private val inventoryService: InventoryService,
) {

    @PostMapping("/reserve")
    fun reserve(@Valid @RequestBody request: ReserveRequest): ResponseEntity<ReservationResponse> {
        val outcome = inventoryService.reserve(
            request.productId!!, request.orderId!!, request.quantity!!,
        )
        val body = ReservationResponse.from(outcome.reservation, outcome.idempotent)
        val status = if (outcome.idempotent) HttpStatus.OK else HttpStatus.CREATED
        return ResponseEntity.status(status).body(body)
    }

    @PostMapping("/release")
    fun release(@Valid @RequestBody request: ReleaseRequest): ReservationResponse {
        val outcome = inventoryService.release(request.productId!!, request.orderId!!)
        return ReservationResponse.from(outcome.reservation, outcome.idempotent)
    }

    @GetMapping("/{productId}")
    fun get(@PathVariable productId: Long): InventoryResponse =
        InventoryResponse.from(inventoryService.getByProductId(productId))
}
