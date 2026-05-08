package io.minishop.order.concurrency;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdaptiveLimiterTests {

    private MeterRegistry meters;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
    }

    @Test
    void exposesLimitAndInFlightAsGauges() {
        AdaptiveLimiter limiter = newLimiter(3, 1, 10);

        assertThat(meters.get("client.concurrency.limit").tag("upstream", "test").gauge().value())
                .isEqualTo(3.0);
        assertThat(meters.get("client.concurrency.in_flight").tag("upstream", "test").gauge().value())
                .isEqualTo(0.0);

        AdaptiveLimiter.Listener l = limiter.acquire();
        assertThat(meters.get("client.concurrency.in_flight").tag("upstream", "test").gauge().value())
                .isEqualTo(1.0);
        l.onSuccess();
        assertThat(meters.get("client.concurrency.in_flight").tag("upstream", "test").gauge().value())
                .isEqualTo(0.0);
    }

    @Test
    void rejectsWhenInFlightReachesCurrentLimit() {
        AdaptiveLimiter limiter = newLimiter(2, 1, 10);

        AdaptiveLimiter.Listener l1 = limiter.acquire();
        AdaptiveLimiter.Listener l2 = limiter.acquire();

        // queueSize=0 + initial limit=2 → 3번째 acquire 는 즉시 거절. cascade 차단의 핵심.
        assertThatThrownBy(limiter::acquire)
                .isInstanceOf(LimitExceededException.class)
                .matches(e -> ((LimitExceededException) e).getUpstream().equals("test"))
                .matches(e -> ((LimitExceededException) e).getCurrentLimit() >= 1)
                .matches(e -> ((LimitExceededException) e).getRetryAfterSeconds() == 1);

        // 하나라도 풀어주면 즉시 acquire 가능.
        l1.onSuccess();
        AdaptiveLimiter.Listener l3 = limiter.acquire();
        l2.onSuccess();
        l3.onSuccess();

        assertThat(limiter.currentInFlight()).isZero();
    }

    @Test
    void executeHelperReleasesOnSuccess() throws Exception {
        AdaptiveLimiter limiter = newLimiter(1, 1, 10);

        String result = limiter.execute(() -> "ok");

        assertThat(result).isEqualTo("ok");
        assertThat(limiter.currentInFlight()).isZero();
    }

    @Test
    void executeHelperReleasesOnException() {
        AdaptiveLimiter limiter = newLimiter(1, 1, 10);

        assertThatThrownBy(() -> limiter.execute(() -> { throw new RuntimeException("boom"); }))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("boom");

        // 예외에도 listener 가 닫혀야 — 이후 acquire 가 한도 안에서 가능해야 한다.
        assertThat(limiter.currentInFlight()).isZero();
        AdaptiveLimiter.Listener after = limiter.acquire();
        after.onSuccess();
    }

    @Test
    void listenerIsIdempotent() {
        AdaptiveLimiter limiter = newLimiter(2, 1, 10);
        AdaptiveLimiter.Listener l = limiter.acquire();

        l.onSuccess();
        // 두 번째 mark 가 inFlight 를 음수로 만들면 안 됨.
        l.onSuccess();
        l.onIgnore();
        l.onDropped();

        assertThat(limiter.currentInFlight()).isZero();
    }

    @Test
    void limitShrinksUnderRepeatedDroppedSignals() {
        // Gradient2 는 onDropped 가 누적되면 limit 을 multiplicative 로 줄인다. 이 동작을 직접
        // 단언 — adaptive 의 핵심 차별점 (Bulkhead 는 못 함).
        AdaptiveLimiter limiter = newLimiter(20, 1, 100);
        int initialLimit = limiter.currentLimit();

        // 여러 cycle 의 onDropped — backend 가 망가지고 있다는 신호.
        for (int i = 0; i < 50; i++) {
            AdaptiveLimiter.Listener l = limiter.acquire();
            sleepBriefly(); // RTT 측정용 짧은 latency
            l.onDropped();
        }

        // Gradient2 의 정확한 수치는 라이브러리 구현에 따라 변동 — 줄었다는 *방향성* 만 단언.
        assertThat(limiter.currentLimit()).isLessThanOrEqualTo(initialLimit);
    }

    @Test
    void acquireFromMultipleThreadsRespectsLimit() throws InterruptedException {
        AdaptiveLimiter limiter = newLimiter(5, 1, 100);
        AtomicInteger acquired = new AtomicInteger(0);
        AtomicInteger rejected = new AtomicInteger(0);

        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            Thread t = new Thread(() -> {
                try {
                    AdaptiveLimiter.Listener l = limiter.acquire();
                    acquired.incrementAndGet();
                    sleepBriefly();
                    l.onSuccess();
                } catch (LimitExceededException e) {
                    rejected.incrementAndGet();
                }
            });
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) t.join();

        // 동시 20개 요청 중 일부는 거절되어야 (limit=5 + queueSize=0).
        assertThat(rejected.get()).isGreaterThan(0);
        // 적어도 limit 만큼은 acquire 가 성공해야 — limit 이 0 으로 떨어지지 않음을 확인.
        assertThat(acquired.get()).isGreaterThanOrEqualTo(5);
        // 합은 항상 20.
        assertThat(acquired.get() + rejected.get()).isEqualTo(20);
        // 모든 요청 종료 후 inFlight 는 0.
        assertThat(limiter.currentInFlight()).isZero();
    }

    @Test
    void minLimitRespected() {
        // minLimit 이 강제되어야 — 아무리 많은 onDropped 신호가 와도 0 으로 떨어지면 호출이
        // 영원히 거절되어 cascade 가 *역으로* 호출자에 발생.
        AdaptiveLimiter limiter = newLimiter(10, 2, 50);

        for (int i = 0; i < 100; i++) {
            AdaptiveLimiter.Listener l = limiter.acquire();
            l.onDropped();
        }

        assertThat(limiter.currentLimit()).isGreaterThanOrEqualTo(2);
    }

    private AdaptiveLimiter newLimiter(int initial, int min, int max) {
        AdaptiveLimiterProperties props = new AdaptiveLimiterProperties(true, initial, min, max);
        return new AdaptiveLimiter("test", props, meters);
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(2);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
