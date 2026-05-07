package io.minishop.inventory.exception;

public class ReservationNotFoundException extends RuntimeException {
    public ReservationNotFoundException(Long orderId, Long productId) {
        super("Reservation not found for order=" + orderId + " product=" + productId);
    }
}
