package io.minishop.payment

import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication

@SpringBootApplication
open class PaymentServiceApplication

fun main(args: Array<String>) {
    SpringApplication.run(PaymentServiceApplication::class.java, *args)
}
