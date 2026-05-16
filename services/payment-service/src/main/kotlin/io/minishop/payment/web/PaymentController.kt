package io.minishop.payment.web

import io.minishop.payment.domain.PaymentStatus
import io.minishop.payment.service.PaymentService
import io.minishop.payment.web.dto.CreatePaymentRequest
import io.minishop.payment.web.dto.PaymentResponse
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.util.UriComponentsBuilder

@RestController
@RequestMapping("/payments")
open class PaymentController(private val paymentService: PaymentService) {

    @PostMapping
    fun create(@Valid @RequestBody request: CreatePaymentRequest): ResponseEntity<PaymentResponse> {
        val payment = paymentService.processPayment(request)
        val body = PaymentResponse.from(payment)
        val status = if (payment.status == PaymentStatus.SUCCESS) {
            HttpStatus.CREATED
        } else {
            HttpStatus.PAYMENT_REQUIRED // 402: 결제 시도는 했지만 PG에서 거절
        }

        return ResponseEntity
            .status(status)
            .location(
                UriComponentsBuilder.fromPath("/payments/{id}").buildAndExpand(payment.id).toUri(),
            )
            .body(body)
    }

    @GetMapping("/{id}")
    fun getById(@PathVariable id: Long): PaymentResponse = PaymentResponse.from(paymentService.getById(id))
}
