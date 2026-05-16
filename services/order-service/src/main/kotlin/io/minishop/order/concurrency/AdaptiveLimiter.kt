package io.minishop.order.concurrency

import com.netflix.concurrency.limits.Limiter
import com.netflix.concurrency.limits.limit.Gradient2Limit
import com.netflix.concurrency.limits.limiter.SimpleLimiter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import org.slf4j.LoggerFactory
import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicInteger

/**
 * Netflix concurrency-limits 의 [SimpleLimiter] 를 서비스마다 독립 한도로 감싼 wrapper.
 *
 * 알고리즘 — [Gradient2Limit]: TCP Vegas 와 같은 latency 기반 적응 방식.
 *   1. 최근 RTT (round-trip time) 분포의 long-term 평균 (rttNoLoad) 과 short-term 평균 (rtt) 을 비교.
 *   2. 두 값의 비율 (gradient) 이 1.0 근처면 backend 가 안정 → limit 천천히 증가 (probe).
 *   3. gradient 가 작으면 (= short-term latency 가 long-term 보다 더 크다 = backend 가 느려졌다) limit
 *      즉시 축소 (multiplicative decrease).
 *
 * => backend 가 느려지면 호출자가 줄을 서지 않고 즉시 실패한다 (cascade 차단). backend 가
 * 회복되면 latency 가 정상화되고 limit 도 다시 증가한다 — 사람이 손대지 않아도 자동.
 *
 * Resilience4j 의 Bulkhead 는 정적 한도 — backend 가 갑자기 느려져도 똑같은 N 명이 들어가서
 * cascade 가 그대로 발생. adaptive 한도가 필요한 이유.
 */
class AdaptiveLimiter(
    upstream: String,
    props: AdaptiveLimiterProperties,
    meterRegistry: MeterRegistry,
) {

    @get:JvmName("upstream")
    val upstream: String = upstream

    private val gradient: Gradient2Limit = Gradient2Limit.newBuilder()
        .initialLimit(props.initialLimit)
        .minLimit(props.minLimit)
        .maxConcurrency(props.maxLimit)
        // queueSize=0 — 한도 초과 시 즉시 실패 (줄에 끼지 않음). cascade 방지의 핵심.
        .queueSize(0)
        .build()

    // SimpleLimiter.newBuilder() 는 generic 이 build() 에 붙어 있다 (`<ContextT> build()`).
    // Kotlin 에서는 build 의 type argument 를 explicit 으로 지정해야 한다.
    private val limiter: Limiter<Void> = SimpleLimiter.newBuilder()
        .limit(gradient)
        .named("upstream-$upstream")
        .build<Void>()

    private val inFlight = AtomicInteger(0)

    init {
        // 운영 가시성: limit / in-flight 둘 다 노출. limit 이 minLimit 까지 떨어지면 외부 의존이
        // 감당 못하는 상태 — 알람으로 받을 수 있다.
        Gauge.builder("client.concurrency.limit", gradient) { it.limit.toDouble() }
            .description("adaptive limiter 가 현재 허용 중인 동시 진행 요청 수")
            .tags(listOf(Tag.of("upstream", upstream)))
            .register(meterRegistry)

        Gauge.builder("client.concurrency.in_flight", inFlight) { it.get().toDouble() }
            .description("지금 이 순간 진행 중인 요청 수")
            .tags(listOf(Tag.of("upstream", upstream)))
            .register(meterRegistry)

        log.info(
            "AdaptiveLimiter[{}] initialized — initial={} min={} max={}",
            upstream, props.initialLimit, props.minLimit, props.maxLimit,
        )
    }

    /**
     * 한 요청에 대한 acquire. 한도 초과면 [LimitExceededException] 즉시.
     *
     * 리턴된 [Listener] 는 호출자가 onSuccess / onIgnore / onDropped 중 하나를 반드시
     * 호출해서 latency 측정을 알고리즘에 돌려주어야 한다.
     * [execute] 헬퍼가 이걸 try-finally 로 감싸준다.
     */
    fun acquire(): Listener {
        val opt = limiter.acquire(null)
        if (opt.isEmpty) {
            val currentLimit = gradient.limit
            // Retry-After: limit 이 작을수록 짧게 다시 시도하라고 신호. 실험적으로 1s 가 균형점.
            // 너무 짧으면 클라이언트가 다시 와서 같은 거절을 만남 (불필요 비용), 너무 길면 backend
            // 회복 후에도 클라이언트가 늦게 돌아옴.
            throw LimitExceededException(upstream, currentLimit, 1)
        }
        inFlight.incrementAndGet()
        return Listener(opt.get(), inFlight)
    }

    /**
     * 헬퍼 — 한 callable 을 limiter 안에서 실행. 정상 / 예외 모두 적절히 listener 마감.
     *
     * - 정상 종료 → onSuccess (latency 가 알고리즘에 반영)
     * - 예외 → onIgnore (이번 latency 는 신호로 안 씀 — backend 느림이 아니라 우리쪽 코드 오류)
     */
    @Throws(Exception::class)
    fun <T> execute(action: Callable<T>): T {
        val l = acquire()
        try {
            val result = action.call()
            l.onSuccess()
            return result
        } catch (e: Exception) {
            l.onIgnore()
            throw e
        }
    }

    fun currentLimit(): Int = gradient.limit

    fun currentInFlight(): Int = inFlight.get()

    /** wrapper — 호출자가 명시적으로 마감 시그널을 보내야 하는 형태가 필요할 때. */
    class Listener internal constructor(
        private val delegate: Limiter.Listener,
        private val inFlight: AtomicInteger,
    ) {
        private var closed = false

        @Synchronized
        fun onSuccess() {
            if (closed) return
            try {
                delegate.onSuccess()
            } finally {
                inFlight.decrementAndGet()
                closed = true
            }
        }

        @Synchronized
        fun onIgnore() {
            if (closed) return
            try {
                delegate.onIgnore()
            } finally {
                inFlight.decrementAndGet()
                closed = true
            }
        }

        @Synchronized
        fun onDropped() {
            if (closed) return
            try {
                delegate.onDropped()
            } finally {
                inFlight.decrementAndGet()
                closed = true
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(AdaptiveLimiter::class.java)
    }
}
