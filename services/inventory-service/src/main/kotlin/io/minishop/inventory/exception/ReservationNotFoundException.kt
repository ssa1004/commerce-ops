package io.minishop.inventory.exception

class ReservationNotFoundException(orderId: Long, productId: Long) :
    RuntimeException("Reservation not found for order=$orderId product=$productId")
