package io.minishop.order

import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication

@SpringBootApplication
class OrderServiceApplication

fun main(args: Array<String>) {
    SpringApplication.run(OrderServiceApplication::class.java, *args)
}
