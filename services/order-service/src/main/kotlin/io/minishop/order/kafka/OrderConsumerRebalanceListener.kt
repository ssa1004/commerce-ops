package io.minishop.order.kafka

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.common.TopicPartition
import org.slf4j.LoggerFactory
import org.springframework.kafka.listener.ConsumerAwareRebalanceListener
import java.util.HashSet
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/**
 * Kafka consumer rebalance 시점의 안전 지점 핸들러.
 *
 * rebalance 가 일어나는 순간 (인스턴스 추가/제거, 죽은 인스턴스 회수, partition 추가) consumer
 * 가 *지금 가지고 있는 partition 을 잠깐 놓는다*. 아무 처리 없이 놓으면 가장 흔히 두 가지가 깨진다:
 *
 * 1. **중복 처리** — 처리는 끝났지만 ack 가 안 나간 메시지의 offset 이 commit 되지 않은 채
 *    partition 이 다른 인스턴스로 넘어가면, 새 인스턴스는 *그 메시지부터 다시* 받는다.
 *    (멱등성으로 흡수 가능하지만 *ack 직전의 작은 윈도우* 만큼 중복.)
 * 2. **처리 누락 가능** — auto-commit=true 인 환경에선 처리 *전* 에 ack 가 나간 상태로
 *    rebalance 가 일어나면 그 메시지가 누락된 채 다음 인스턴스가 *다음 offset 부터* 받는다.
 *    (우리는 이미 enable-auto-commit=false 라 이 사고는 못 일어나지만, 정책의 *명시 보호선*.)
 *
 * 핵심 동작:
 * - [onPartitionsRevokedBeforeCommit] — partition 이 떠나기 *직전*. 진행 중인 처리의
 *   마지막 offset 을 동기 commit 으로 강제 flush. spring-kafka 가 호출 직후 `consumer.commitSync(offsets)`
 *   를 동기 호출 — rebalance 가 *commit 완료 후* 진행되도록 보장.
 * - [onPartitionsAssigned] — 새 partition 을 받은 직후. 위치를 로그로 남겨, 사고 회고
 *   에서 "rebalance 후 어느 인스턴스가 어디서 시작했는지" 가 보이게 한다.
 * - [onPartitionsLost] — *비정상 회수* (heartbeat 끊김 등). cooperative-sticky 에서는
 *   drop 후 즉시 재할당받지 않는다 — *지금 들고 있던 inflight 처리는 폐기* 가 정책. 메트릭
 *   으로 알람.
 *
 * cooperative-sticky assignor (KIP-429) 와 결합 — incremental rebalance 라 *변경된 partition
 * 만* revoke/assign 되어 정상 인스턴스의 처리는 멈추지 않는다. 그래도 자기 partition 이 revoke
 * 대상이면 위 보장이 필요하다.
 *
 * ADR-021 참조.
 */
class OrderConsumerRebalanceListener(
    private val groupTag: String,
    private val meterRegistry: MeterRegistry,
) : ConsumerAwareRebalanceListener {

    /**
     * 현재 할당된 partition 수. gauge 에 strong-reference 로 한 번만 등록 — [onPartitionsAssigned]
     * 마다 `meterRegistry.gauge(name, tags, partitions.size())` 를 호출하면 매 rebalance 마다 새
     * Integer (박싱) 가 등록되어 GC 후 NaN 으로 보일 수 있다 (Micrometer 가 첫 등록한 instance 만 들고 있음).
     */
    private val assignedPartitions = AtomicInteger(0)

    init {
        Gauge.builder("kafka.consumer.partitions.assigned", assignedPartitions) { it.toDouble() }
            .tags(Tags.of("group", groupTag))
            .description("현재 이 인스턴스에 할당된 partition 수 (rebalance 시점 갱신)")
            .register(meterRegistry)
    }

    /**
     * commit *전* 에 호출. 여기서 마지막 commit 을 명시 보장 — spring-kafka 가 ack-mode=MANUAL_IMMEDIATE
     * 에서도 *consumer 자체의 마지막 alignment* 를 commit 한다.
     *
     * 본 메서드 자체는 동기 commit 의 *호출 시점* 일 뿐, 실제 commit 은 spring-kafka 가
     * onPartitionsRevoked 의 hook 으로 한다. 메트릭과 로그는 사고 회고용.
     */
    override fun onPartitionsRevokedBeforeCommit(consumer: Consumer<*, *>, partitions: Collection<TopicPartition>) {
        if (partitions.isEmpty()) return
        meterRegistry.counter(
            "kafka.consumer.rebalance",
            Tags.of("group", groupTag, "phase", "revoke_before_commit"),
        ).increment()
        log.info(
            "Kafka rebalance — revoke before commit: group={} partitions={}",
            groupTag, formatPartitions(partitions),
        )
    }

    /**
     * commit *후*. partition 이 다른 인스턴스로 넘어가기 직전의 *진짜 끝*. 진행 중 처리는 이미 끝났
     * 거나 (commit 됨) 아직 안 끝나 다음 인스턴스가 다시 받게 된다 (멱등성으로 흡수).
     */
    override fun onPartitionsRevokedAfterCommit(consumer: Consumer<*, *>, partitions: Collection<TopicPartition>) {
        if (partitions.isEmpty()) return
        meterRegistry.counter(
            "kafka.consumer.rebalance",
            Tags.of("group", groupTag, "phase", "revoke_after_commit"),
        ).increment()
        // cooperative-sticky 에선 onPartitionsAssigned 가 증가분만 받기 때문에, revoke 가 일어나는
        // 시점에 게이지를 줄여둬야 현재 상태와 어긋나지 않는다. consumer.assignment() 가 가장 정확하지만
        // 호출 시점에 따라 revoke 적용 전/후가 다를 수 있어 *delta 적용* 으로 단순화.
        assignedPartitions.updateAndGet { prev -> max(0, prev - partitions.size) }
    }

    /**
     * 새 partition 할당 직후. 시작 위치 (last committed offset) 를 로그 — 사고 회고에서 "이
     * 인스턴스가 어디서 시작했나" 의 1차 단서.
     */
    override fun onPartitionsAssigned(consumer: Consumer<*, *>, partitions: Collection<TopicPartition>) {
        if (partitions.isEmpty()) return
        meterRegistry.counter(
            "kafka.consumer.rebalance",
            Tags.of("group", groupTag, "phase", "assign"),
        ).increment()
        // cooperative-sticky 는 *증가분만* 들어오므로 += , eager assignor 도 revoke 에서 줄여둔 상태라
        // delta 합산이 일관 결과.
        assignedPartitions.addAndGet(partitions.size)
        try {
            val committed = consumer.committed(HashSet(partitions))
            log.info(
                "Kafka rebalance — assigned: group={} partitions={} startOffsets={}",
                groupTag, formatPartitions(partitions), formatOffsets(committed),
            )
        } catch (e: Exception) {
            // committed() 호출이 broker 와의 일시 통신 실패로 throw 가능. 로깅용이므로 흡수.
            log.warn(
                "Kafka rebalance — assigned but committed() lookup failed: group={} partitions={} cause={}",
                groupTag, formatPartitions(partitions), e.javaClass.simpleName,
            )
        }
    }

    /**
     * heartbeat 끊김 등 *비정상 회수*. cooperative-sticky 에서도 발생 — 이 케이스는 진행 중 inflight
     * 가 *폐기* 다. 알람 추적용.
     */
    override fun onPartitionsLost(consumer: Consumer<*, *>, partitions: Collection<TopicPartition>) {
        if (partitions.isEmpty()) return
        meterRegistry.counter(
            "kafka.consumer.rebalance",
            Tags.of("group", groupTag, "phase", "lost"),
        ).increment()
        // lost 는 revoke 콜백을 거치지 않으므로 게이지를 직접 차감.
        assignedPartitions.updateAndGet { prev -> max(0, prev - partitions.size) }
        log.warn(
            "Kafka rebalance — partitions lost (heartbeat / connection broke): group={} partitions={}",
            groupTag, formatPartitions(partitions),
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(OrderConsumerRebalanceListener::class.java)

        private fun formatPartitions(partitions: Collection<TopicPartition>): String {
            val sb = StringBuilder("[")
            var first = true
            for (tp in partitions) {
                if (!first) sb.append(",")
                sb.append(tp.topic()).append(":").append(tp.partition())
                first = false
            }
            return sb.append("]").toString()
        }

        private fun formatOffsets(offsets: Map<TopicPartition, OffsetAndMetadata>?): String {
            if (offsets.isNullOrEmpty()) return "{}"
            val sb = StringBuilder("{")
            var first = true
            for ((tp, meta) in offsets) {
                if (!first) sb.append(",")
                sb.append(tp.topic()).append(":").append(tp.partition())
                    .append("=").append(meta?.offset()?.toString() ?: "null")
                first = false
            }
            return sb.append("}").toString()
        }
    }
}
