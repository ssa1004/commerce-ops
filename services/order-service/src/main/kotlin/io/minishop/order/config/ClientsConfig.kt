package io.minishop.order.config

import io.micrometer.core.instrument.MeterRegistry
import io.minishop.order.concurrency.AdaptiveLimiter
import io.minishop.order.concurrency.AdaptiveLimiterInterceptor
import io.minishop.order.concurrency.AdaptiveLimiterProperties
import io.minishop.order.retry.RetryInterceptor
import io.minishop.order.retry.RetryProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.client.RestClient
import java.time.Duration

@Configuration
@EnableConfigurationProperties(
    PaymentClientProperties::class,
    InventoryClientProperties::class,
    AdaptiveLimiterProperties::class,
    RetryProperties::class,
)
class ClientsConfig {

    @Bean(name = ["paymentLimiter"])
    fun paymentLimiter(props: AdaptiveLimiterProperties, meterRegistry: MeterRegistry): AdaptiveLimiter =
        AdaptiveLimiter("payment", props, meterRegistry)

    @Bean(name = ["inventoryLimiter"])
    fun inventoryLimiter(props: AdaptiveLimiterProperties, meterRegistry: MeterRegistry): AdaptiveLimiter =
        AdaptiveLimiter("inventory", props, meterRegistry)

    @Bean(name = ["paymentRestClient"])
    fun paymentRestClient(
        props: PaymentClientProperties,
        paymentLimiter: AdaptiveLimiter,
        limiterProps: AdaptiveLimiterProperties,
        retryProps: RetryProperties,
        meterRegistry: MeterRegistry,
    ): RestClient = restClient(
        props.url, props.connectTimeoutMs, props.readTimeoutMs,
        buildInterceptors("payment", paymentLimiter, limiterProps, retryProps, meterRegistry),
    )

    @Bean(name = ["inventoryRestClient"])
    fun inventoryRestClient(
        props: InventoryClientProperties,
        inventoryLimiter: AdaptiveLimiter,
        limiterProps: AdaptiveLimiterProperties,
        retryProps: RetryProperties,
        meterRegistry: MeterRegistry,
    ): RestClient = restClient(
        props.url, props.connectTimeoutMs, props.readTimeoutMs,
        buildInterceptors("inventory", inventoryLimiter, limiterProps, retryProps, meterRegistry),
    )

    @Bean
    fun transactionTemplate(txManager: PlatformTransactionManager): TransactionTemplate =
        TransactionTemplate(txManager)

    companion object {
        /**
         * interceptor chain 등록 순서:
         * 1. **RetryInterceptor** (바깥) — transient 오류에 retry. 매 attempt 마다 chain 의 안쪽
         *    (limiter) 을 다시 통과 → backend 가 부하를 못 받는 동안 limiter 한도가 줄어 retry 도
         *    함께 제한됨. 두 메커니즘이 직교적으로 결합.
         * 2. **AdaptiveLimiterInterceptor** (안쪽) — 동시 진행 중 요청 수 제어.
         *
         * Spring 의 ClientHttpRequestInterceptor chain 은 list 순서대로 호출되며, 첫 번째가 가장
         * 바깥. ADR-022 참조.
         */
        private fun buildInterceptors(
            upstream: String,
            limiter: AdaptiveLimiter,
            limiterProps: AdaptiveLimiterProperties,
            retryProps: RetryProperties,
            meterRegistry: MeterRegistry,
        ): List<ClientHttpRequestInterceptor> {
            val chain = ArrayList<ClientHttpRequestInterceptor>(2)
            if (retryProps.enabled) {
                chain.add(RetryInterceptor(upstream, retryProps, meterRegistry))
            }
            if (limiterProps.enabled) {
                chain.add(AdaptiveLimiterInterceptor(limiter))
            }
            return chain
        }

        private fun restClient(
            baseUrl: String,
            connectMs: Long,
            readMs: Long,
            interceptorChain: List<ClientHttpRequestInterceptor>,
        ): RestClient {
            val factory = SimpleClientHttpRequestFactory()
            factory.setConnectTimeout(Duration.ofMillis(connectMs))
            factory.setReadTimeout(Duration.ofMillis(readMs))
            var b = RestClient.builder()
                .requestFactory(factory)
                .baseUrl(baseUrl)
            if (interceptorChain.isNotEmpty()) {
                b = b.requestInterceptors { interceptors -> interceptors.addAll(interceptorChain) }
            }
            return b.build()
        }
    }
}
