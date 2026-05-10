package io.minishop.order.outbox;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link OutboxPoller#processOne()} 의 *실패 회계 (attempts vs maxAttempts)* 회귀 테스트.
 *
 * <p>특히 {@code InterruptedException} 경로가 {@code handleFailure} 를 거쳐야 한다는 정합성을
 * 박아 두는 게 목표 — 직접 {@code markAttemptFailed} 만 호출하면 인터럽트가 반복되는 행이
 * maxAttempts 에 도달해도 영구 PENDING 으로 남는 wiring 결함이 있었다.
 */
class OutboxPollerFailureTests {

    private OutboxRepository repository;
    private OutboxProperties props;
    private SimpleMeterRegistry registry;
    /** processOne() 은 package-private 가 아니라 private — 회계 시나리오를 한 행 단위로 검증하려면 reflection. */
    private Method processOne;

    @BeforeEach
    void setUp() throws Exception {
        repository = mock(OutboxRepository.class);
        // maxAttempts=3 → 첫 두 실패는 retry, 3 번째는 markFailed.
        props = new OutboxProperties(new OutboxProperties.Poller(true, 1000L, 10, 3, 50L));
        registry = new SimpleMeterRegistry();

        processOne = OutboxPoller.class.getDeclaredMethod("processOne");
        processOne.setAccessible(true);
    }

    @Test
    void executionException_belowMax_retainsPendingAndCountsRetry() throws Exception {
        OutboxEvent event = pendingEvent();
        when(repository.findNextPendingForUpdate()).thenReturn(Optional.of(event));
        OutboxPoller poller = pollerWith(failingKafka());

        boolean handled = invoke(poller);

        assertThat(handled).isTrue();
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(registry.counter("outbox.publish",
                "topic", "order.events", "outcome", "retry").count()).isEqualTo(1.0);
    }

    @Test
    void executionException_atMax_marksFailedAndCountsFailed() throws Exception {
        OutboxEvent event = pendingEvent();
        // maxAttempts=3 — 이미 두 번 실패한 상태. 다음 실패가 markFailed 가 되어야 함.
        bumpAttempts(event, 2);
        when(repository.findNextPendingForUpdate()).thenReturn(Optional.of(event));
        OutboxPoller poller = pollerWith(failingKafka());

        boolean handled = invoke(poller);

        assertThat(handled).isTrue();
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(event.getAttempts()).isEqualTo(3);
        assertThat(registry.counter("outbox.publish",
                "topic", "order.events", "outcome", "failed").count()).isEqualTo(1.0);
    }

    @Test
    void interrupt_belowMax_retainsPendingAndCountsInterrupted() throws Exception {
        OutboxEvent event = pendingEvent();
        when(repository.findNextPendingForUpdate()).thenReturn(Optional.of(event));
        OutboxPoller poller = pollerWith(interruptingKafka());

        boolean handled = invoke(poller);

        assertThat(handled).isTrue();
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(registry.counter("outbox.publish",
                "topic", "order.events", "outcome", "interrupted").count()).isEqualTo(1.0);
        // 인터럽트는 곧장 다시 set 한다 (현 스레드 상태). 다음 테스트에 누수되지 않게 정리.
        assertThat(Thread.interrupted()).isTrue();
    }

    /**
     * 핵심 회귀 테스트 — 인터럽트가 maxAttempts 에 도달했을 때 *반드시* {@code markFailed} 가 호출되어
     * 행이 종결되어야 한다. 이전 구현은 인터럽트 경로에서 {@code markAttemptFailed} 만 호출 →
     * 같은 행이 영구 PENDING 으로 남았다.
     */
    @Test
    void interrupt_atMax_marksFailed() throws Exception {
        OutboxEvent event = pendingEvent();
        bumpAttempts(event, 2);
        when(repository.findNextPendingForUpdate()).thenReturn(Optional.of(event));
        OutboxPoller poller = pollerWith(interruptingKafka());

        boolean handled = invoke(poller);

        assertThat(handled).isTrue();
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(event.getAttempts()).isEqualTo(3);
        assertThat(registry.counter("outbox.publish",
                "topic", "order.events", "outcome", "interrupted").count()).isEqualTo(1.0);
        assertThat(Thread.interrupted()).isTrue();
    }

    @Test
    void noPendingEvent_returnsFalseAndDoesNotRecord() throws Exception {
        when(repository.findNextPendingForUpdate()).thenReturn(Optional.empty());
        OutboxPoller poller = pollerWith(failingKafka());

        boolean handled = invoke(poller);

        assertThat(handled).isFalse();
        assertThat(registry.find("outbox.publish").counters()).isEmpty();
    }

    // ---------- helpers ----------

    private boolean invoke(OutboxPoller poller) throws Exception {
        return (Boolean) processOne.invoke(poller);
    }

    private OutboxPoller pollerWith(KafkaTemplate<String, String> kafka) {
        return new OutboxPoller(repository, kafka,
                new TransactionTemplate(new SyncTxManager()), props, registry);
    }

    private static OutboxEvent pendingEvent() {
        return OutboxEvent.pending("Order", 42L, "Order.Created", "order.events", "{}");
    }

    /** 시뮬레이션상 이미 N 번 실패한 상태로 만들기. */
    private static void bumpAttempts(OutboxEvent event, int times) {
        for (int i = 0; i < times; i++) event.markAttemptFailed("simulated");
    }

    /** Kafka send → 항상 ExecutionException — broker 거부 / 네트워크 실패의 일반형. */
    @SuppressWarnings("unchecked")
    private static KafkaTemplate<String, String> failingKafka() {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("simulated kafka failure"));
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(failed);
        return kafka;
    }

    /**
     * Kafka send → {@code .get(timeout)} 호출이 InterruptedException 으로 깨지는 future.
     * 운영에서는 process 종료 신호 / pool stop 으로 워커가 인터럽트 받는 케이스.
     */
    @SuppressWarnings("unchecked")
    private static KafkaTemplate<String, String> interruptingKafka() {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        CompletableFuture<SendResult<String, String>> interrupting = new CompletableFuture<>() {
            @Override
            public SendResult<String, String> get(long timeout, TimeUnit unit) throws InterruptedException {
                Thread.currentThread().interrupt();
                throw new InterruptedException("simulated");
            }
        };
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(interrupting);
        return kafka;
    }

    /**
     * 트랜잭션 콜백을 그대로 실행만 한다 — 실 DB 트랜잭션이 없는 단위 테스트용.
     * commit / rollback 도 no-op. {@link OutboxPoller} 의 정상/예외 경로는 stub 이 결정.
     */
    private static class SyncTxManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {}

        @Override
        public void rollback(TransactionStatus status) {}
    }
}
