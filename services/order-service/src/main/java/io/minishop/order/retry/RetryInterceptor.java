package io.minishop.order.retry;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.lang.NonNull;

import java.io.IOException;
import java.net.SocketTimeoutException;

/**
 * RestClient 의 {@link ClientHttpRequestInterceptor} — transient 오류에 대해 exponential backoff +
 * jitter 로 retry. 4xx 는 retry 안 함 (client 잘못이므로 같은 결과). 5xx 와 IOException /
 * SocketTimeoutException 만 retry.
 *
 * <p>chain 위치 — {@link io.minishop.order.concurrency.AdaptiveLimiterInterceptor} 의 바깥
 * (interceptor 등록 순서로 보장). 매 retry 마다 limiter 가 재진입 → backend 가 부하를 못 받는
 * 동안 retry 가 limiter 한도를 함께 줄여 cascade 차단을 강화. ADR-022 참조.
 *
 * <p>MDC 키 {@code retry-attempt} 를 매 시도마다 set/clear — logback 패턴이 attempt 번호를 함께
 * 찍어 사고 회고에서 "이 호출이 몇 번째 retry 였는지" 가 직접 보인다.
 */
public class RetryInterceptor implements ClientHttpRequestInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RetryInterceptor.class);
    private static final String MDC_KEY = "retry-attempt";

    private final String upstream;
    private final RetryProperties props;
    private final RetryBackoff backoff;
    private final MeterRegistry meterRegistry;
    private final Sleeper sleeper;

    public RetryInterceptor(String upstream, RetryProperties props, MeterRegistry meterRegistry) {
        this(upstream, props, new RetryBackoff(props), meterRegistry, Thread::sleep);
    }

    /** 테스트용 — 가짜 sleeper / 가짜 backoff 주입. */
    RetryInterceptor(String upstream, RetryProperties props, RetryBackoff backoff,
                     MeterRegistry meterRegistry, Sleeper sleeper) {
        this.upstream = upstream;
        this.props = props;
        this.backoff = backoff;
        this.meterRegistry = meterRegistry;
        this.sleeper = sleeper;
    }

    @Override
    @NonNull
    public ClientHttpResponse intercept(@NonNull HttpRequest request,
                                        @NonNull byte[] body,
                                        @NonNull ClientHttpRequestExecution execution) throws IOException {
        int maxAttempts = Math.max(1, props.maxAttempts());
        int attempt = 1;
        while (true) {
            // MDC 는 attempt > 1 일 때만 set — 첫 시도는 unmarked 가 자연스러움 (정상 호출과 구분).
            if (attempt > 1) {
                MDC.put(MDC_KEY, Integer.toString(attempt));
            }
            try {
                ClientHttpResponse response = execution.execute(request, body);
                int status = response.getStatusCode().value();
                if (props.retryOn5xx() && status >= 500 && attempt < maxAttempts) {
                    long wait = backoff.delayMillis(attempt);
                    meterRegistry.counter("http.client.retry",
                            Tags.of("upstream", upstream, "outcome", "retry_5xx")).increment();
                    log.warn("Retry {}/{} after {}ms — upstream={} status={}",
                            attempt, maxAttempts, wait, upstream, status);
                    closeQuietly(response);
                    sleepOrInterrupt(wait);
                    attempt++;
                    continue;
                }
                if (attempt > 1) {
                    meterRegistry.counter("http.client.retry",
                            Tags.of("upstream", upstream, "outcome", "recovered")).increment();
                }
                return response;
            } catch (SocketTimeoutException e) {
                if (attempt >= maxAttempts) {
                    meterRegistry.counter("http.client.retry",
                            Tags.of("upstream", upstream, "outcome", "exhausted_timeout")).increment();
                    throw e;
                }
                long wait = backoff.delayMillis(attempt);
                meterRegistry.counter("http.client.retry",
                        Tags.of("upstream", upstream, "outcome", "retry_timeout")).increment();
                log.warn("Retry {}/{} after {}ms — upstream={} cause=SocketTimeoutException",
                        attempt, maxAttempts, wait, upstream);
                sleepOrInterrupt(wait);
                attempt++;
            } catch (IOException e) {
                if (attempt >= maxAttempts) {
                    meterRegistry.counter("http.client.retry",
                            Tags.of("upstream", upstream, "outcome", "exhausted_io")).increment();
                    throw e;
                }
                long wait = backoff.delayMillis(attempt);
                meterRegistry.counter("http.client.retry",
                        Tags.of("upstream", upstream, "outcome", "retry_io")).increment();
                log.warn("Retry {}/{} after {}ms — upstream={} cause={}",
                        attempt, maxAttempts, wait, upstream, e.getClass().getSimpleName());
                sleepOrInterrupt(wait);
                attempt++;
            } finally {
                if (attempt > 1) {
                    MDC.remove(MDC_KEY);
                }
            }
        }
    }

    private void sleepOrInterrupt(long ms) throws IOException {
        if (ms <= 0) return;
        try {
            sleeper.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Retry sleep interrupted for upstream=" + upstream, e);
        }
    }

    private static void closeQuietly(ClientHttpResponse response) {
        try {
            response.close();
        } catch (Exception ignore) {
            // 본문이 큰 경우 다음 retry 전에 닫아 connection 돌려주기 — 실패는 무시.
        }
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long ms) throws InterruptedException;
    }
}
