package io.minishop.inventory.web;

import io.minishop.inventory.service.InventoryService;
import io.minishop.inventory.service.InventoryService.ReservationOutcome;
import io.minishop.inventory.web.dto.InventoryResponse;
import io.minishop.inventory.web.dto.ReleaseRequest;
import io.minishop.inventory.web.dto.ReservationResponse;
import io.minishop.inventory.web.dto.ReserveRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/inventories")
public class InventoryController {

    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @PostMapping("/reserve")
    public ResponseEntity<ReservationResponse> reserve(@Valid @RequestBody ReserveRequest request) {
        ReservationOutcome outcome = inventoryService.reserve(
                request.productId(), request.orderId(), request.quantity()
        );
        ReservationResponse body = ReservationResponse.from(outcome.reservation(), outcome.idempotent());
        HttpStatus status = outcome.idempotent() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(body);
    }

    @PostMapping("/release")
    public ReservationResponse release(@Valid @RequestBody ReleaseRequest request) {
        ReservationOutcome outcome = inventoryService.release(request.productId(), request.orderId());
        return ReservationResponse.from(outcome.reservation(), outcome.idempotent());
    }

    @GetMapping("/{productId}")
    public InventoryResponse get(@PathVariable Long productId) {
        return InventoryResponse.from(inventoryService.getByProductId(productId));
    }
}
