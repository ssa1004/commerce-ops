package io.minishop.inventory.exception

class ProductNotFoundException(productId: Long) :
    RuntimeException("Product not found: $productId")
