package io.minishop.order.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.minishop.order.concurrency.AdaptiveLimiter;
import io.minishop.order.concurrency.AdaptiveLimiterInterceptor;
import io.minishop.order.concurrency.AdaptiveLimiterProperties;
import io.minishop.order.retry.RetryInterceptor;
import io.minishop.order.retry.RetryProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Configuration
@EnableConfigurationProperties({
        PaymentClientProperties.class,
        InventoryClientProperties.class,
        AdaptiveLimiterProperties.class,
        RetryProperties.class
})
public class ClientsConfig {

    @Bean(name = "paymentLimiter")
    public AdaptiveLimiter paymentLimiter(AdaptiveLimiterProperties props, MeterRegistry meterRegistry) {
        return new AdaptiveLimiter("payment", props, meterRegistry);
    }

    @Bean(name = "inventoryLimiter")
    public AdaptiveLimiter inventoryLimiter(AdaptiveLimiterProperties props, MeterRegistry meterRegistry) {
        return new AdaptiveLimiter("inventory", props, meterRegistry);
    }

    @Bean(name = "paymentRestClient")
    public RestClient paymentRestClient(PaymentClientProperties props,
                                        AdaptiveLimiter paymentLimiter,
                                        AdaptiveLimiterProperties limiterProps,
                                        RetryProperties retryProps,
                                        MeterRegistry meterRegistry) {
        return restClient(props.url(), props.connectTimeoutMs(), props.readTimeoutMs(),
                buildInterceptors("payment", paymentLimiter, limiterProps, retryProps, meterRegistry));
    }

    @Bean(name = "inventoryRestClient")
    public RestClient inventoryRestClient(InventoryClientProperties props,
                                          AdaptiveLimiter inventoryLimiter,
                                          AdaptiveLimiterProperties limiterProps,
                                          RetryProperties retryProps,
                                          MeterRegistry meterRegistry) {
        return restClient(props.url(), props.connectTimeoutMs(), props.readTimeoutMs(),
                buildInterceptors("inventory", inventoryLimiter, limiterProps, retryProps, meterRegistry));
    }

    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager txManager) {
        return new TransactionTemplate(txManager);
    }

    /**
     * interceptor chain 등록 순서:
     * <ol>
     *   <li><b>RetryInterceptor</b> (바깥) — transient 오류에 retry. 매 attempt 마다 chain 의 안쪽
     *       (limiter) 을 다시 통과 → backend 가 망가지는 동안 limiter 한도가 줄어 retry 도 함께
     *       제한됨. 두 메커니즘이 직교적으로 결합.</li>
     *   <li><b>AdaptiveLimiterInterceptor</b> (안쪽) — 동시 진행 중 요청 수 제어.</li>
     * </ol>
     * <p>Spring 의 ClientHttpRequestInterceptor chain 은 list 순서대로 호출되며, 첫 번째가 가장
     * 바깥. ADR-022 참조.
     */
    private static List<ClientHttpRequestInterceptor> buildInterceptors(
            String upstream,
            AdaptiveLimiter limiter,
            AdaptiveLimiterProperties limiterProps,
            RetryProperties retryProps,
            MeterRegistry meterRegistry) {
        List<ClientHttpRequestInterceptor> chain = new ArrayList<>(2);
        if (retryProps.enabled()) {
            chain.add(new RetryInterceptor(upstream, retryProps, meterRegistry));
        }
        if (limiterProps.enabled()) {
            chain.add(new AdaptiveLimiterInterceptor(limiter));
        }
        return chain;
    }

    private static RestClient restClient(String baseUrl, long connectMs, long readMs,
                                         List<ClientHttpRequestInterceptor> interceptorChain) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectMs));
        factory.setReadTimeout(Duration.ofMillis(readMs));
        RestClient.Builder b = RestClient.builder()
                .requestFactory(factory)
                .baseUrl(baseUrl);
        if (!interceptorChain.isEmpty()) {
            b = b.requestInterceptors(interceptors -> interceptors.addAll(interceptorChain));
        }
        return b.build();
    }
}
