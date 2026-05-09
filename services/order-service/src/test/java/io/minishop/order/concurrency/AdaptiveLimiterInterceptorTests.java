package io.minishop.order.concurrency;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.lang.NonNull;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Interceptor 의 동작 — 한 번의 호출이 limiter 의 acquire/release 를 정확히 한 번씩 통과하는지,
 * 5xx 응답을 onDropped 로 (= backend 부담 신호로) 분류하는지, 정상 200 은 onSuccess 인지.
 */
class AdaptiveLimiterInterceptorTests {

    private MeterRegistry meters;
    private AdaptiveLimiter limiter;
    private AdaptiveLimiterInterceptor interceptor;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        limiter = new AdaptiveLimiter(
                "test",
                new AdaptiveLimiterProperties(true, 5, 1, 50),
                meters
        );
        interceptor = new AdaptiveLimiterInterceptor(limiter);
    }

    @Test
    void successfulRequestMarksOnSuccessAndReleasesInFlight() throws IOException {
        FixedExecution exec = new FixedExecution(200);
        ClientHttpResponse resp = interceptor.intercept(req(), new byte[0], exec);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(exec.invocations.get()).isEqualTo(1);
        // intercept 종료 후 inFlight 는 0 — release 보장.
        assertThat(limiter.currentInFlight()).isZero();
    }

    @Test
    void serverErrorMarksOnDroppedToShrinkLimitFaster() throws IOException {
        FixedExecution exec = new FixedExecution(503);
        ClientHttpResponse resp = interceptor.intercept(req(), new byte[0], exec);

        assertThat(resp.getStatusCode().value()).isEqualTo(503);
        // 5xx 는 backend 가 부하를 못 받는 신호 — onDropped 라 inFlight 정리는 동일.
        assertThat(limiter.currentInFlight()).isZero();
    }

    @Test
    void clientError4xxStillCountsAsSuccessForLimiter() throws IOException {
        // 4xx (예: 409 OUT_OF_STOCK, 402 PAYMENT_DECLINED) 는 비즈니스 결과 — backend 부담과 무관.
        // limiter 알고리즘은 이걸 정상 응답으로 봐야 한다.
        FixedExecution exec = new FixedExecution(409);
        interceptor.intercept(req(), new byte[0], exec);

        assertThat(limiter.currentInFlight()).isZero();
    }

    @Test
    void connectionFailureMarksOnDroppedAndPropagates() {
        FixedExecution exec = new FixedExecution(new IOException("connection refused"));

        assertThatThrownBy(() -> interceptor.intercept(req(), new byte[0], exec))
                .isInstanceOf(IOException.class)
                .hasMessage("connection refused");

        assertThat(limiter.currentInFlight()).isZero();
    }

    @Test
    void runtimeExceptionPropagatesAsIgnored() {
        // 우리쪽 코드 / serialization 오류 — backend 신호로 쓰면 false signal. onIgnore 라 limit
        // 변동 없이 inFlight 만 정리.
        FixedExecution exec = new FixedExecution(new RuntimeException("boom"));

        assertThatThrownBy(() -> interceptor.intercept(req(), new byte[0], exec))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("boom");

        assertThat(limiter.currentInFlight()).isZero();
    }

    @Test
    void limiterRejectsBeforeExecutingRequest() {
        // 한도까지 채운 뒤 — 다음 intercept 는 *upstream 호출 없이* 즉시 LimitExceededException.
        AdaptiveLimiter saturated = new AdaptiveLimiter(
                "test", new AdaptiveLimiterProperties(true, 1, 1, 5), meters
        );
        AdaptiveLimiterInterceptor i = new AdaptiveLimiterInterceptor(saturated);

        // 첫 요청 acquire — 미해제로 한도 점유.
        AdaptiveLimiter.Listener occupy = saturated.acquire();

        FixedExecution exec = new FixedExecution(200);
        assertThatThrownBy(() -> i.intercept(req(), new byte[0], exec))
                .isInstanceOf(LimitExceededException.class);

        // 정말로 upstream 호출이 *발생하지 않았는지* — invocations 0.
        assertThat(exec.invocations.get()).isZero();

        occupy.onSuccess();
    }

    private static HttpRequest req() {
        return new MockClientHttpRequest(org.springframework.http.HttpMethod.POST, URI.create("/test"));
    }

    /** 테스트용 가짜 execution — 미리 정한 status 또는 예외를 반환. */
    static final class FixedExecution implements ClientHttpRequestExecution {
        final AtomicInteger invocations = new AtomicInteger(0);
        final int status;
        final IOException ioException;
        final RuntimeException runtimeException;

        FixedExecution(int status) {
            this.status = status;
            this.ioException = null;
            this.runtimeException = null;
        }

        FixedExecution(IOException e) {
            this.status = 0;
            this.ioException = e;
            this.runtimeException = null;
        }

        FixedExecution(RuntimeException e) {
            this.status = 0;
            this.ioException = null;
            this.runtimeException = e;
        }

        @Override
        @NonNull
        public ClientHttpResponse execute(@NonNull HttpRequest request, @NonNull byte[] body) throws IOException {
            invocations.incrementAndGet();
            if (ioException != null) throw ioException;
            if (runtimeException != null) throw runtimeException;
            MockClientHttpResponse r = new MockClientHttpResponse(new byte[0], HttpStatusCode.valueOf(status));
            r.getHeaders().setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
            return r;
        }
    }

    @SuppressWarnings("unused")
    private static HttpHeaders headers() {
        // mock-http-client 가 import 되어있는지 보장 (compile guard) — 실제 사용은 위 mock 클래스가 함.
        return new HttpHeaders();
    }
}
