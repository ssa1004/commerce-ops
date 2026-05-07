package io.minishop.order.kafka;

import io.minishop.order.outbox.OutboxProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(OutboxProperties.class)
public class KafkaConfig {
    // Spring Boot autoconfigures KafkaTemplate<String, String> from spring.kafka.* properties.
    // Keep this class minimal — the only role is enabling @Scheduled (for OutboxPoller)
    // and binding OutboxProperties.
}
