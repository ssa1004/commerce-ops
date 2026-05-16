package io.minishop.inventory.exception

class OutOfStockException(
    productId: Long,
    requested: Int,
    available: Int,
) : RuntimeException(
    "Out of stock for product $productId (requested=$requested, available=$available)",
) {

    @get:JvmName("getProductId")
    val productId: Long = productId

    @get:JvmName("getRequested")
    val requested: Int = requested

    @get:JvmName("getAvailable")
    val available: Int = available
}
