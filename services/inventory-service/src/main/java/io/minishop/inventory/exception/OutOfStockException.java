package io.minishop.inventory.exception;

public class OutOfStockException extends RuntimeException {
    private final Long productId;
    private final int requested;
    private final int available;

    public OutOfStockException(Long productId, int requested, int available) {
        super("Out of stock for product " + productId + " (requested=" + requested + ", available=" + available + ")");
        this.productId = productId;
        this.requested = requested;
        this.available = available;
    }

    public Long getProductId() { return productId; }
    public int getRequested() { return requested; }
    public int getAvailable() { return available; }
}
