package io.minishop.payment.web;

import io.minishop.payment.domain.Payment;
import io.minishop.payment.domain.PaymentStatus;
import io.minishop.payment.service.PaymentService;
import io.minishop.payment.web.dto.CreatePaymentRequest;
import io.minishop.payment.web.dto.PaymentResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
@RequestMapping("/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> create(@Valid @RequestBody CreatePaymentRequest request) {
        Payment payment = paymentService.processPayment(request);
        PaymentResponse body = PaymentResponse.from(payment);
        HttpStatus status = payment.getStatus() == PaymentStatus.SUCCESS
                ? HttpStatus.CREATED
                : HttpStatus.PAYMENT_REQUIRED; // 402: 결제 시도는 했지만 PG에서 거절

        return ResponseEntity
                .status(status)
                .location(UriComponentsBuilder.fromPath("/payments/{id}").buildAndExpand(payment.getId()).toUri())
                .body(body);
    }

    @GetMapping("/{id}")
    public PaymentResponse getById(@PathVariable Long id) {
        return PaymentResponse.from(paymentService.getById(id));
    }
}
