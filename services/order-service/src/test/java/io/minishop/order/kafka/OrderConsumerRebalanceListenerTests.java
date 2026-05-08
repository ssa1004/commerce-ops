package io.minishop.order.kafka;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrderConsumerRebalanceListenerTests {

    private SimpleMeterRegistry registry;
    private OrderConsumerRebalanceListener listener;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        listener = new OrderConsumerRebalanceListener("order-service.test-group", registry);
    }

    @Test
    void revokeBeforeCommitIncrementsMetric() {
        listener.onPartitionsRevokedBeforeCommit(mock(Consumer.class), List.of(new TopicPartition("inventory.events", 0)));

        double v = registry.counter("kafka.consumer.rebalance",
                "group", "order-service.test-group", "phase", "revoke_before_commit").count();
        assertThat(v).isEqualTo(1.0);
    }

    @Test
    void revokeAfterCommitIncrementsMetric() {
        listener.onPartitionsRevokedAfterCommit(mock(Consumer.class), List.of(new TopicPartition("payment.events", 0)));

        double v = registry.counter("kafka.consumer.rebalance",
                "group", "order-service.test-group", "phase", "revoke_after_commit").count();
        assertThat(v).isEqualTo(1.0);
    }

    @Test
    void assignedIncrementsMetricAndPublishesPartitionGauge() {
        @SuppressWarnings("unchecked")
        Consumer<String, String> consumer = mock(Consumer.class);
        Set<TopicPartition> partitions = Set.of(
                new TopicPartition("inventory.events", 0),
                new TopicPartition("payment.events", 1));
        Map<TopicPartition, OffsetAndMetadata> committed = new HashMap<>();
        committed.put(new TopicPartition("inventory.events", 0), new OffsetAndMetadata(42L));
        committed.put(new TopicPartition("payment.events", 1), null); // 새로 만든 partition
        when(consumer.committed(any(Set.class))).thenReturn(committed);

        listener.onPartitionsAssigned(consumer, partitions);

        double assigns = registry.counter("kafka.consumer.rebalance",
                "group", "order-service.test-group", "phase", "assign").count();
        assertThat(assigns).isEqualTo(1.0);
        Double gauge = registry.find("kafka.consumer.partitions.assigned")
                .tag("group", "order-service.test-group").gauge().value();
        assertThat(gauge).isEqualTo(2.0);
    }

    @Test
    void assignedWithCommittedLookupFailureSwallowsAndStillCountsRebalance() {
        @SuppressWarnings("unchecked")
        Consumer<String, String> consumer = mock(Consumer.class);
        when(consumer.committed(any(Set.class)))
                .thenThrow(new IllegalStateException("broker unreachable"));

        // 예외가 메트릭/로그용 lookup 에서 발생해도 listener 자체는 throw 하지 않아야 함.
        listener.onPartitionsAssigned(consumer, List.of(new TopicPartition("inventory.events", 0)));

        double assigns = registry.counter("kafka.consumer.rebalance",
                "group", "order-service.test-group", "phase", "assign").count();
        assertThat(assigns).isEqualTo(1.0);
    }

    @Test
    void lostIncrementsMetric() {
        listener.onPartitionsLost(mock(Consumer.class), List.of(new TopicPartition("payment.events", 2)));

        double v = registry.counter("kafka.consumer.rebalance",
                "group", "order-service.test-group", "phase", "lost").count();
        assertThat(v).isEqualTo(1.0);
    }

    @Test
    void emptyPartitionsAreNoop() {
        // 모든 hook 이 빈 collection 으로 호출돼도 메트릭 0 유지.
        listener.onPartitionsRevokedBeforeCommit(mock(Consumer.class), Collections.emptyList());
        listener.onPartitionsRevokedAfterCommit(mock(Consumer.class), Collections.emptyList());
        listener.onPartitionsAssigned(mock(Consumer.class), Collections.emptyList());
        listener.onPartitionsLost(mock(Consumer.class), Collections.emptyList());

        // counter 자체가 등록되지 않았는지 확인.
        assertThat(registry.find("kafka.consumer.rebalance").counters()).isEmpty();
    }

    @Test
    void unknownGroupTagIsAccepted() {
        // group.id 가 비어 있는 (테스트용) container 에 대비. 실제 listener 가 (unknown) 으로 돌더라도
        // throw 없이 메트릭이 그 태그로 들어와야 함.
        AtomicReference<Throwable> caught = new AtomicReference<>();
        OrderConsumerRebalanceListener anonymous = new OrderConsumerRebalanceListener("(unknown)", registry);
        try {
            anonymous.onPartitionsAssigned(mock(Consumer.class), List.of(new TopicPartition("x", 0)));
        } catch (Throwable t) {
            caught.set(t);
        }
        assertThat(caught.get()).isNull();
    }
}
