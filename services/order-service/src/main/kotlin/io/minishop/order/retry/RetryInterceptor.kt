package io.minishop.order.retry

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.http.HttpRequest
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.ClientHttpResponse
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * RestClient 의 [ClientHttpRequestInterceptor] — transient 오류에 대해 exponential backoff +
 * jitter 로 retry. 4xx 는 retry 안 함 (client 잘못이므로 같은 결과). 5xx 와 IOException /
 * SocketTimeoutException 만 retry.
 *
 * chain 위치 — [io.minishop.order.concurrency.AdaptiveLimiterInterceptor] 의 바깥
 * (interceptor 등록 순서로 보장). 매 retry 마다 limiter 가 재진입 → backend 가 부하를 못 받는
 * 동안 retry 가 limiter 한도를 함께 줄여 cascade 차단을 강화. ADR-022 참조.
 *
 * MDC 키 `retry-attempt` 를 매 시도마다 set/clear — logback 패턴이 attempt 번호를 함께
 * 찍어 사고 회고에서 "이 호출이 몇 번째 retry 였는지" 가 직접 보인다.
 */
class RetryInterceptor internal constructor(
    private val upstream: String,
    private val props: RetryProperties,
    private val backoff: RetryBackoff,
    private val meterRegistry: MeterRegistry,
    private val sleeper: Sleeper,
) : ClientHttpRequestInterceptor {

    constructor(upstream: String, props: RetryProperties, meterRegistry: MeterRegistry) :
        this(upstream, props, RetryBackoff(props), meterRegistry, Sleeper { ms -> Thread.sleep(ms) })

    @Throws(IOException::class)
    override fun intercept(
        request: HttpRequest,
        body: ByteArray,
        execution: ClientHttpRequestExecution,
    ): ClientHttpResponse {
        val maxAttempts = if (props.maxAttempts < 1) 1 else props.maxAttempts
        var attempt = 1
        while (true) {
            // MDC 는 attempt > 1 일 때만 set — 첫 시도는 unmarked 가 자연스러움 (정상 호출과 구분).
            if (attempt > 1) {
                MDC.put(MDC_KEY, attempt.toString())
            }
            try {
                val response = execution.execute(request, body)
                val status = response.statusCode.value()
                if (props.retryOn5xx && status >= 500 && attempt < maxAttempts) {
                    val wait = backoff.delayMillis(attempt)
                    meterRegistry.counter(
                        "http.client.retry",
                        Tags.of("upstream", upstream, "outcome", "retry_5xx"),
                    ).increment()
                    log.warn(
                        "Retry {}/{} after {}ms — upstream={} status={}",
                        attempt, maxAttempts, wait, upstream, status,
                    )
                    closeQuietly(response)
                    sleepOrInterrupt(wait)
                    attempt++
                    continue
                }
                if (attempt > 1) {
                    meterRegistry.counter(
                        "http.client.retry",
                        Tags.of("upstream", upstream, "outcome", "recovered"),
                    ).increment()
                }
                return response
            } catch (e: SocketTimeoutException) {
                if (attempt >= maxAttempts) {
                    meterRegistry.counter(
                        "http.client.retry",
                        Tags.of("upstream", upstream, "outcome", "exhausted_timeout"),
                    ).increment()
                    throw e
                }
                val wait = backoff.delayMillis(attempt)
                meterRegistry.counter(
                    "http.client.retry",
                    Tags.of("upstream", upstream, "outcome", "retry_timeout"),
                ).increment()
                log.warn(
                    "Retry {}/{} after {}ms — upstream={} cause=SocketTimeoutException",
                    attempt, maxAttempts, wait, upstream,
                )
                sleepOrInterrupt(wait)
                attempt++
            } catch (e: IOException) {
                if (attempt >= maxAttempts) {
                    meterRegistry.counter(
                        "http.client.retry",
                        Tags.of("upstream", upstream, "outcome", "exhausted_io"),
                    ).increment()
                    throw e
                }
                val wait = backoff.delayMillis(attempt)
                meterRegistry.counter(
                    "http.client.retry",
                    Tags.of("upstream", upstream, "outcome", "retry_io"),
                ).increment()
                log.warn(
                    "Retry {}/{} after {}ms — upstream={} cause={}",
                    attempt, maxAttempts, wait, upstream, e.javaClass.simpleName,
                )
                sleepOrInterrupt(wait)
                attempt++
            } finally {
                if (attempt > 1) {
                    MDC.remove(MDC_KEY)
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun sleepOrInterrupt(ms: Long) {
        if (ms <= 0) return
        try {
            sleeper.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Retry sleep interrupted for upstream=$upstream", e)
        }
    }

    fun interface Sleeper {
        @Throws(InterruptedException::class)
        fun sleep(ms: Long)
    }

    companion object {
        private val log = LoggerFactory.getLogger(RetryInterceptor::class.java)
        private const val MDC_KEY = "retry-attempt"

        private fun closeQuietly(response: ClientHttpResponse) {
            try {
                response.close()
            } catch (_: Exception) {
                // 본문이 큰 경우 다음 retry 전에 닫아 connection 돌려주기 — 실패는 무시.
            }
        }
    }
}
