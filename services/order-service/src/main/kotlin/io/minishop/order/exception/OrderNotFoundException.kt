package io.minishop.order.exception

class OrderNotFoundException(id: Long) : RuntimeException("Order not found: $id")
