package io.minishop.payment.web

import io.minishop.payment.config.MockPgProperties
import io.minishop.payment.web.dto.PgChargeRequest
import io.minishop.payment.web.dto.PgChargeResponse
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

/**
 * 외부 결제 게이트웨이 (PG — Payment Gateway, 결제사) 를 흉내내는 컨트롤러.
 * 평소엔 평균 latencyMeanMs 정도 걸리고, 일정 확률로 5xx 를 던지거나 timeoutMs 만큼
 * 멈춰서 호출자의 read timeout 을 유도한다.
 *
 * 운영 환경에선 enabled=false. 평소 트래픽 데모와 chaos 시나리오 (일부러 장애를 주입해 시스템
 * 반응을 보는 실험) 의 신호원 역할.
 */
@RestController
@RequestMapping("/mock-pg")
@ConditionalOnProperty(
    prefix = "mini-shop.mock-pg",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
open class MockPgController(private val props: MockPgProperties) {

    @PostMapping("/charge")
    fun charge(@Valid @RequestBody request: PgChargeRequest): PgChargeResponse {
        val rng = ThreadLocalRandom.current()

        val dice = rng.nextDouble()
        if (dice < props.timeoutRate) {
            log.warn("mock-pg: simulating timeout for paymentId={}", request.paymentId)
            sleep(props.timeoutMs)
            // 이 return 이 실행되기 전에 호출자의 read timeout 이 먼저 발화한다 (의도된 in-doubt 시뮬레이션).
            return PgChargeResponse.fail("simulated timeout")
        }

        val latency = sampleLatency(rng)
        sleep(latency)

        if (dice < props.timeoutRate + props.failureRate) {
            log.info(
                "mock-pg: simulating failure for paymentId={} (latency={}ms)",
                request.paymentId,
                latency,
            )
            throw MockPgServerException("simulated PG 5xx")
        }

        return PgChargeResponse.ok("pg-" + UUID.randomUUID())
    }

    @GetMapping("/config")
    fun config(): MockPgProperties = props

    private fun sampleLatency(rng: ThreadLocalRandom): Long {
        val sample = rng.nextGaussian() * props.latencyStddevMs + props.latencyMeanMs
        return Math.max(1L, sample.toLong())
    }

    class MockPgServerException(message: String) : RuntimeException(message)

    companion object {
        private val log = LoggerFactory.getLogger(MockPgController::class.java)

        private fun sleep(ms: Long) {
            try {
                Thread.sleep(ms)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }
}
