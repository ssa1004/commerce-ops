package io.minishop.payment.service

import io.minishop.payment.config.PgProperties
import io.minishop.payment.web.dto.PgChargeRequest
import io.minishop.payment.web.dto.PgChargeResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatusCode
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient

@Component
class PgClient(pgRestClient: RestClient, private val props: PgProperties) {

    private val client: RestClient = pgRestClient

    fun charge(request: PgChargeRequest): PgChargeResponse {
        try {
            return client.post()
                .uri(props.url)
                .body(request)
                .retrieve()
                .onStatus(HttpStatusCode::isError) { _, res ->
                    throw PgFailureException("PG returned " + res.statusCode)
                }
                .body(PgChargeResponse::class.java)
                ?: throw PgFailureException("PG returned empty body")
        } catch (e: ResourceAccessException) {
            log.warn("PG call timed out or unreachable: {}", e.message)
            throw PgFailureException("PG timeout/unreachable: " + e.message, e)
        }
    }

    /**
     * PG 호출이 실패하거나 응답이 정상이 아닐 때 던지는 예외.
     * Java 호환을 위해 두 생성자 (`String`, `String + Throwable`) 그대로 유지.
     */
    class PgFailureException : RuntimeException {
        constructor(message: String) : super(message)
        constructor(message: String, cause: Throwable) : super(message, cause)
    }

    companion object {
        private val log = LoggerFactory.getLogger(PgClient::class.java)
    }
}
