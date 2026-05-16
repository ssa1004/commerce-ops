package io.minishop.payment.exception

class PaymentNotFoundException(id: Long) : RuntimeException("Payment not found: $id")
