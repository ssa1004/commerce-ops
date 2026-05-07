package io.minishop.order.kafka;

import io.minishop.order.outbox.OutboxProperties;
import io.minishop.order.reconciliation.ReconciliationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableKafka
@EnableConfigurationProperties({OutboxProperties.class, ReconciliationProperties.class})
public class KafkaConfig {
    // Spring Boot autoconfigures KafkaTemplate<String, String> from spring.kafka.* properties.
    // Keep this class minimal — only enables scheduling (for OutboxPoller / ReconciliationJob)
    // and Kafka listener support, plus binds the relevant config records.
}
