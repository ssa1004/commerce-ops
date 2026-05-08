package io.minishop.order.concurrency;

import com.netflix.concurrency.limits.Limiter;
import com.netflix.concurrency.limits.limit.Gradient2Limit;
import com.netflix.concurrency.limits.limiter.SimpleLimiter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Netflix concurrency-limits 의 {@link SimpleLimiter} 를 *서비스마다 독립* 한도로 감싼 wrapper.
 *
 * <p>알고리즘 — {@link Gradient2Limit}: TCP Vegas 와 같은 *latency 기반 적응* 방식.
 * <ol>
 *   <li>최근 RTT (round-trip time) 분포의 long-term 평균 (rttNoLoad) 과 short-term 평균 (rtt) 을 비교.</li>
 *   <li>두 값의 비율 (gradient) 이 1.0 근처면 backend 가 안정 → limit 천천히 증가 (probe).</li>
 *   <li>gradient 가 작으면 (= short-term latency 가 long-term 보다 더 크다 = backend 가 느려졌다) limit
 *       즉시 축소 (multiplicative decrease).</li>
 * </ol>
 *
 * <p>=> backend 가 느려지면 *호출자가 줄을 서지 않고* 즉시 실패한다 (cascade 차단). backend 가
 * 회복되면 latency 가 정상화되고 limit 도 다시 증가한다 — 사람이 손대지 않아도 자동.
 *
 * <p>Resilience4j 의 Bulkhead 는 *정적* 한도 — backend 가 갑자기 느려져도 똑같은 N 명이 들어가서
 * cascade 가 그대로 발생. AWS / Netflix / 카카오의 backend mesh 가 adaptive 를 쓰는 이유.
 */
public class AdaptiveLimiter {

    private static final Logger log = LoggerFactory.getLogger(AdaptiveLimiter.class);

    private final String upstream;
    private final Limiter<Void> limiter;
    private final Gradient2Limit gradient;
    private final AtomicInteger inFlight = new AtomicInteger(0);

    public AdaptiveLimiter(String upstream, AdaptiveLimiterProperties props, MeterRegistry meterRegistry) {
        this.upstream = upstream;
        this.gradient = Gradient2Limit.newBuilder()
                .initialLimit(props.initialLimit())
                .minLimit(props.minLimit())
                .maxConcurrency(props.maxLimit())
                // queueSize=0 — 한도 초과 시 즉시 실패 (줄에 끼지 않음). cascade 방지의 핵심.
                .queueSize(0)
                .build();
        this.limiter = SimpleLimiter.newBuilder()
                .limit(gradient)
                .named("upstream-" + upstream)
                .build();

        // 운영 가시성: limit / in-flight 둘 다 노출. limit 이 minLimit 까지 떨어지면 외부 의존이
        // 감당 못하는 상태 — 알람으로 받을 수 있다.
        Gauge.builder("client.concurrency.limit", gradient, Gradient2Limit::getLimit)
                .description("adaptive limiter 가 현재 허용 중인 동시 진행 요청 수")
                .tags(java.util.List.of(Tag.of("upstream", upstream)))
                .register(meterRegistry);

        Gauge.builder("client.concurrency.in_flight", inFlight, AtomicInteger::get)
                .description("지금 이 순간 진행 중인 요청 수")
                .tags(java.util.List.of(Tag.of("upstream", upstream)))
                .register(meterRegistry);

        log.info("AdaptiveLimiter[{}] initialized — initial={} min={} max={}",
                upstream, props.initialLimit(), props.minLimit(), props.maxLimit());
    }

    /**
     * 한 요청에 대한 acquire. 한도 초과면 {@link LimitExceededException} 즉시.
     *
     * <p>리턴된 {@link Listener} 는 호출자가 *반드시 onSuccess / onIgnore / onDropped 중 하나* 를 호출
     * 해서 latency 측정을 알고리즘에 돌려주어야 한다. {@link #execute(java.util.concurrent.Callable)}
     * 헬퍼가 이걸 try-finally 로 감싸준다.
     */
    public Listener acquire() {
        Optional<Limiter.Listener> opt = limiter.acquire(null);
        if (opt.isEmpty()) {
            int currentLimit = gradient.getLimit();
            // Retry-After: limit 이 작을수록 짧게 다시 시도하라고 신호. 실험적으로 1s 가 균형점.
            // 너무 짧으면 클라이언트가 다시 와서 같은 거절을 만남 (불필요 비용), 너무 길면 backend
            // 회복 후에도 클라이언트가 늦게 돌아옴.
            throw new LimitExceededException(upstream, currentLimit, 1);
        }
        inFlight.incrementAndGet();
        return new Listener(opt.get(), inFlight);
    }

    /**
     * 헬퍼 — 한 callable 을 limiter 안에서 실행. 정상 / 예외 모두 적절히 listener 마감.
     *
     * <ul>
     *   <li>정상 종료 → onSuccess (latency 가 알고리즘에 반영)</li>
     *   <li>예외 → onIgnore (이번 latency 는 신호로 안 씀 — backend 느림이 아니라 우리쪽 코드 오류)</li>
     * </ul>
     */
    public <T> T execute(java.util.concurrent.Callable<T> action) throws Exception {
        Listener l = acquire();
        try {
            T result = action.call();
            l.onSuccess();
            return result;
        } catch (Exception e) {
            l.onIgnore();
            throw e;
        }
    }

    public int currentLimit() {
        return gradient.getLimit();
    }

    public int currentInFlight() {
        return inFlight.get();
    }

    public String upstream() {
        return upstream;
    }

    /** wrapper — 호출자가 명시적으로 마감 시그널을 보내야 하는 형태가 필요할 때. */
    public static final class Listener {
        private final Limiter.Listener delegate;
        private final AtomicInteger inFlight;
        private boolean closed = false;

        Listener(Limiter.Listener delegate, AtomicInteger inFlight) {
            this.delegate = delegate;
            this.inFlight = inFlight;
        }

        public synchronized void onSuccess() {
            if (closed) return;
            try { delegate.onSuccess(); } finally { inFlight.decrementAndGet(); closed = true; }
        }

        public synchronized void onIgnore() {
            if (closed) return;
            try { delegate.onIgnore(); } finally { inFlight.decrementAndGet(); closed = true; }
        }

        public synchronized void onDropped() {
            if (closed) return;
            try { delegate.onDropped(); } finally { inFlight.decrementAndGet(); closed = true; }
        }
    }
}
