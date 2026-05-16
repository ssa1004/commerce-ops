package io.minishop.order.service

import io.minishop.order.concurrency.LimitExceededException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import java.math.BigDecimal

@Component
class PaymentClient(
    @Qualifier("paymentRestClient") private val client: RestClient,
) {

    fun charge(orderId: Long, userId: Long, amount: BigDecimal): PaymentResult {
        try {
            return client.post()
                .uri("/payments")
                .body(PaymentRequest(orderId, userId, amount))
                .retrieve()
                .onStatus(
                    { s: HttpStatusCode ->
                        s.value() != HttpStatus.CREATED.value() &&
                            s.value() != HttpStatus.PAYMENT_REQUIRED.value()
                    },
                    { _, res -> throw PaymentInfraException("payment-service returned ${res.statusCode}") },
                )
                .body(PaymentResult::class.java)
                ?: throw PaymentInfraException("payment-service returned empty body")
        } catch (e: LimitExceededException) {
            // adaptive limiter 가 cascade 차단으로 즉시 거절 — payment 가 느려져 우리쪽 한도가
            // 줄었음. OrderService 가 UPSTREAM_LIMITED outcome 으로 매핑 (보상 호출 후 503).
            log.warn(
                "payment call rejected by adaptive limiter (limit={}): {}",
                e.currentLimit, e.message,
            )
            throw e
        } catch (e: ResourceAccessException) {
            log.warn("payment-service unreachable: {}", e.message)
            throw PaymentInfraException("payment-service unreachable: ${e.message}", e)
        }
    }

    @JvmRecord
    data class PaymentRequest(val orderId: Long, val userId: Long, val amount: BigDecimal)

    @JvmRecord
    data class PaymentResult(
        val id: Long?,
        val orderId: Long?,
        val status: String?,        // SUCCESS / FAILED
        val externalRef: String?,
        val failureReason: String?,
    ) {
        @get:JvmName("isSuccess")
        val isSuccess: Boolean
            get() = "SUCCESS" == status
    }

    class PaymentInfraException : RuntimeException {
        constructor(message: String) : super(message)
        constructor(message: String, cause: Throwable) : super(message, cause)
    }

    companion object {
        private val log = LoggerFactory.getLogger(PaymentClient::class.java)
    }
}
