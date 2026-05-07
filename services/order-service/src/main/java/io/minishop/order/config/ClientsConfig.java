package io.minishop.order.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties({PaymentClientProperties.class, InventoryClientProperties.class})
public class ClientsConfig {

    @Bean(name = "paymentRestClient")
    public RestClient paymentRestClient(PaymentClientProperties props) {
        return restClient(props.url(), props.connectTimeoutMs(), props.readTimeoutMs());
    }

    @Bean(name = "inventoryRestClient")
    public RestClient inventoryRestClient(InventoryClientProperties props) {
        return restClient(props.url(), props.connectTimeoutMs(), props.readTimeoutMs());
    }

    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager txManager) {
        return new TransactionTemplate(txManager);
    }

    private static RestClient restClient(String baseUrl, long connectMs, long readMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectMs));
        factory.setReadTimeout(Duration.ofMillis(readMs));
        return RestClient.builder()
                .requestFactory(factory)
                .baseUrl(baseUrl)
                .build();
    }
}
