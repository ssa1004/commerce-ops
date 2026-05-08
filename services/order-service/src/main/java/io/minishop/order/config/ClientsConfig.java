package io.minishop.order.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.minishop.order.concurrency.AdaptiveLimiter;
import io.minishop.order.concurrency.AdaptiveLimiterInterceptor;
import io.minishop.order.concurrency.AdaptiveLimiterProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties({
        PaymentClientProperties.class,
        InventoryClientProperties.class,
        AdaptiveLimiterProperties.class
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
                                        AdaptiveLimiterProperties limiterProps) {
        return restClient(props.url(), props.connectTimeoutMs(), props.readTimeoutMs(),
                limiterProps.enabled() ? new AdaptiveLimiterInterceptor(paymentLimiter) : null);
    }

    @Bean(name = "inventoryRestClient")
    public RestClient inventoryRestClient(InventoryClientProperties props,
                                          AdaptiveLimiter inventoryLimiter,
                                          AdaptiveLimiterProperties limiterProps) {
        return restClient(props.url(), props.connectTimeoutMs(), props.readTimeoutMs(),
                limiterProps.enabled() ? new AdaptiveLimiterInterceptor(inventoryLimiter) : null);
    }

    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager txManager) {
        return new TransactionTemplate(txManager);
    }

    private static RestClient restClient(String baseUrl, long connectMs, long readMs,
                                         ClientHttpRequestInterceptor interceptor) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectMs));
        factory.setReadTimeout(Duration.ofMillis(readMs));
        RestClient.Builder b = RestClient.builder()
                .requestFactory(factory)
                .baseUrl(baseUrl);
        if (interceptor != null) {
            b = b.requestInterceptors(interceptors -> interceptors.add(interceptor));
        }
        return b.build();
    }
}
