package io.minishop.order.kafka

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import io.minishop.order.outbox.OutboxProperties
import io.minishop.order.reconciliation.ReconciliationProperties
import org.apache.kafka.common.TopicPartition
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.kafka.config.ContainerCustomizer
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.util.backoff.FixedBackOff

@Configuration
@EnableScheduling
@EnableKafka
@EnableConfigurationProperties(OutboxProperties::class, ReconciliationProperties::class)
class KafkaConfig {

    /**
     * 컨슈머 에러 핸들러. Spring Boot 의 기본 동작은 영구 재시도 (메시지가 처리 안 되면 무한 반복) 라
     * 일시적 DB/Redis 장애가 가시지 않으면 컨슈머가 멈춰버린다. 짧게 retry 한 뒤 DLT (Dead Letter
     * Topic — 처리 실패 메시지를 따로 보내는 토픽) 로 보내고 다음 메시지로 진행하도록 한다.
     *
     * - `FixedBackOff(0L, 3)`: interval 0 ms 로 3 회까지 즉시 재시도. 일시적 경합 (트랜잭션 충돌
     *   등) 은 잡고, 영속적 실패는 빠르게 DLT 로 보낸다.
     * - DLT 이름은 spring-kafka 기본 규칙 `<원본>.DLT` (예: `payment.events.DLT`).
     * - parse 에러는 컨슈머에서 이미 try/catch 로 흡수하고 있으므로 (poison pill 무한 루프 방지)
     *   여기까지 오는 케이스는 DB/Kafka 일시 장애 같은 인프라성 예외가 주.
     */
    @Bean
    fun kafkaErrorHandler(
        kafkaTemplate: KafkaTemplate<String, String>,
        meterRegistry: MeterRegistry,
    ): DefaultErrorHandler {
        val recoverer = DeadLetterPublishingRecoverer(kafkaTemplate) { record, ex ->
            val dltTopic = record.topic() + ".DLT"
            meterRegistry.counter(
                "inbox.consume",
                Tags.of("topic", record.topic(), "outcome", "dlt"),
            ).increment()
            log.warn(
                "Routing record to DLT after retries exhausted: topic={} dlt={} key={} cause={}",
                record.topic(), dltTopic, record.key(),
                ex?.javaClass?.simpleName ?: "?",
            )
            TopicPartition(dltTopic, record.partition())
        }
        // FixedBackOff(0L, 3) → 첫 시도 + 3 회 재시도 = 총 4 회. 그 이후 DLT 로.
        val handler = DefaultErrorHandler(recoverer, FixedBackOff(0L, 3L))
        handler.setRetryListeners({ record, ex, deliveryAttempt ->
            log.debug(
                "Kafka consumer retry: topic={} attempt={} cause={}",
                record.topic(), deliveryAttempt,
                ex?.javaClass?.simpleName ?: "?",
            )
        })
        return handler
    }

    /**
     * 모든 [ConcurrentMessageListenerContainer] 에 [OrderConsumerRebalanceListener]
     * 를 자동 부착하는 커스터마이저. Spring Boot 가 제공하는 default container factory 를 그대로
     * 쓰되 (ack-mode, deserializer 등 기본 자동 구성 유지), rebalance hook 만 우리 구현으로 갈아끼운다.
     *
     * group.id 가 없는 (테스트용) container 는 group="(unknown)" 으로 들어가지만, 실제 listener
     * 는 모두 `@KafkaListener(groupId=...)` 로 명시 — 운영 path 에서는 정상 group 명이 찍힌다.
     *
     * ADR-021 참조.
     */
    @Bean
    fun rebalanceListenerCustomizer(
        meterRegistry: MeterRegistry,
    ): ContainerCustomizer<String, String, ConcurrentMessageListenerContainer<String, String>> {
        return ContainerCustomizer { container ->
            val groupId = container.containerProperties.groupId
            val groupTag = if (groupId.isNullOrBlank()) "(unknown)" else groupId
            container.containerProperties.setConsumerRebalanceListener(
                OrderConsumerRebalanceListener(groupTag, meterRegistry),
            )
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(KafkaConfig::class.java)
    }
}
