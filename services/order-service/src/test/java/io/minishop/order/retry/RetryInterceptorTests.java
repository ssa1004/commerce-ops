package io.minishop.order.retry;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetryInterceptorTests {

    private SimpleMeterRegistry registry;
    private RetryProperties props;
    private List<Long> sleeps;
    private RetryInterceptor.Sleeper recordingSleeper;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        // jitter=0 → 결정적 backoff (테스트 명확성). base=100, multiplier=2, max=2000.
        props = new RetryProperties(true, 3, 100L, 2000L, 2.0, 0.0, true);
        sleeps = new java.util.ArrayList<>();
        recordingSleeper = sleeps::add; // 실제 sleep 안 함 — 호출만 기록.
    }

    @Test
    void firstSuccessReturnsImmediatelyWithNoMetric() throws IOException {
        StubExecution exec = StubExecution.with(stubResponse(200));
        RetryInterceptor interceptor = new RetryInterceptor("test", props, new RetryBackoff(props, () -> 0.0),
                registry, recordingSleeper);

        ClientHttpResponse r = interceptor.intercept(stubRequest(), new byte[0], exec);

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(exec.calls()).isEqualTo(1);
        assertThat(sleeps).isEmpty();
        assertThat(registry.find("http.client.retry").counters()).isEmpty();
    }

    @Test
    void retriesOn5xxThenSucceedsRecordsRecoveredCounter() throws IOException {
        StubExecution exec = StubExecution.with(stubResponse(503), stubResponse(503), stubResponse(200));
        RetryInterceptor interceptor = new RetryInterceptor("payment", props, new RetryBackoff(props, () -> 0.0),
                registry, recordingSleeper);

        ClientHttpResponse r = interceptor.intercept(stubRequest(), new byte[0], exec);

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(exec.calls()).isEqualTo(3);
        assertThat(sleeps).containsExactly(100L, 200L); // attempt 1 fail → wait 100, attempt 2 fail → wait 200
        assertThat(registry.counter("http.client.retry", "upstream", "payment", "outcome", "retry_5xx").count())
                .isEqualTo(2.0);
        assertThat(registry.counter("http.client.retry", "upstream", "payment", "outcome", "recovered").count())
                .isEqualTo(1.0);
    }

    @Test
    void exhausts5xxRetriesReturnsLast5xxResponse() throws IOException {
        StubExecution exec = StubExecution.with(stubResponse(503), stubResponse(503), stubResponse(503));
        RetryInterceptor interceptor = new RetryInterceptor("payment", props, new RetryBackoff(props, () -> 0.0),
                registry, recordingSleeper);

        ClientHttpResponse r = interceptor.intercept(stubRequest(), new byte[0], exec);

        // 마지막 attempt 의 503 을 그대로 반환 (호출자가 4xx/5xx 처리 책임).
        assertThat(r.getStatusCode().value()).isEqualTo(503);
        assertThat(exec.calls()).isEqualTo(3);
        // attempt 1, 2 시 retry → 2번 wait. attempt 3 은 maxAttempts 도달이라 retry 안 함.
        assertThat(sleeps).hasSize(2);
        assertThat(registry.counter("http.client.retry", "upstream", "payment", "outcome", "retry_5xx").count())
                .isEqualTo(2.0);
    }

    @Test
    void doesNotRetry4xx() throws IOException {
        StubExecution exec = StubExecution.with(stubResponse(404));
        RetryInterceptor interceptor = new RetryInterceptor("inventory", props, new RetryBackoff(props, () -> 0.0),
                registry, recordingSleeper);

        ClientHttpResponse r = interceptor.intercept(stubRequest(), new byte[0], exec);

        assertThat(r.getStatusCode().value()).isEqualTo(404);
        assertThat(exec.calls()).isEqualTo(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void retriesOnSocketTimeoutThenSucceeds() throws IOException {
        StubExecution exec = StubExecution.withSequence(
                () -> { throw new SocketTimeoutException("read timed out"); },
                () -> stubResponse(200)
        );
        RetryInterceptor interceptor = new RetryInterceptor("payment", props, new RetryBackoff(props, () -> 0.0),
                registry, recordingSleeper);

        ClientHttpResponse r = interceptor.intercept(stubRequest(), new byte[0], exec);

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(exec.calls()).isEqualTo(2);
        assertThat(registry.counter("http.client.retry", "upstream", "payment", "outcome", "retry_timeout").count())
                .isEqualTo(1.0);
        assertThat(registry.counter("http.client.retry", "upstream", "payment", "outcome", "recovered").count())
                .isEqualTo(1.0);
    }

    @Test
    void exhaustsTimeoutAttemptsThrowsAndRecordsExhausted() {
        StubExecution exec = StubExecution.withSequence(
                () -> { throw new SocketTimeoutException("t"); },
                () -> { throw new SocketTimeoutException("t"); },
                () -> { throw new SocketTimeoutException("t"); }
        );
        RetryInterceptor interceptor = new RetryInterceptor("payment", props, new RetryBackoff(props, () -> 0.0),
                registry, recordingSleeper);

        assertThatThrownBy(() -> interceptor.intercept(stubRequest(), new byte[0], exec))
                .isInstanceOf(SocketTimeoutException.class);
        assertThat(exec.calls()).isEqualTo(3);
        assertThat(registry.counter("http.client.retry", "upstream", "payment", "outcome", "exhausted_timeout").count())
                .isEqualTo(1.0);
    }

    @Test
    void retriesOnIOExceptionThenSucceeds() throws IOException {
        StubExecution exec = StubExecution.withSequence(
                () -> { throw new IOException("connection reset"); },
                () -> stubResponse(200)
        );
        RetryInterceptor interceptor = new RetryInterceptor("inventory", props, new RetryBackoff(props, () -> 0.0),
                registry, recordingSleeper);

        ClientHttpResponse r = interceptor.intercept(stubRequest(), new byte[0], exec);

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(registry.counter("http.client.retry", "upstream", "inventory", "outcome", "retry_io").count())
                .isEqualTo(1.0);
    }

    @Test
    void retryOn5xxFalseReturns5xxImmediately() throws IOException {
        RetryProperties no5xx = new RetryProperties(true, 3, 100L, 2000L, 2.0, 0.0, false);
        StubExecution exec = StubExecution.with(stubResponse(503));
        RetryInterceptor interceptor = new RetryInterceptor("payment", no5xx, new RetryBackoff(no5xx, () -> 0.0),
                registry, recordingSleeper);

        ClientHttpResponse r = interceptor.intercept(stubRequest(), new byte[0], exec);

        assertThat(r.getStatusCode().value()).isEqualTo(503);
        assertThat(exec.calls()).isEqualTo(1);
        // retryOn5xx=false 라 retry 자체가 일어나지 않음 → 카운터 없음.
        assertThat(registry.find("http.client.retry").counters()).isEmpty();
    }

    @Test
    void interruptedSleepIsWrappedAsIOException() {
        StubExecution exec = StubExecution.withSequence(
                () -> { throw new SocketTimeoutException("t"); }
        );
        RetryInterceptor.Sleeper interrupting = ms -> { throw new InterruptedException("boom"); };
        RetryInterceptor interceptor = new RetryInterceptor("payment", props,
                new RetryBackoff(props, () -> 0.0), registry, interrupting);

        assertThatThrownBy(() -> interceptor.intercept(stubRequest(), new byte[0], exec))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Retry sleep interrupted");
        // interrupt 플래그가 다시 set 됐는지 (호출자 thread 가 cancel 신호를 받는지)
        assertThat(Thread.interrupted()).isTrue(); // interrupted() 가 또 false 로 reset
    }

    @Test
    void backoffSequenceMatchesExpected() throws IOException {
        // 5xx 가 계속 와서 maxAttempts(3) 모두 소진. wait 시퀀스 = [base, base*mult] = [100, 200].
        StubExecution exec = StubExecution.with(stubResponse(503), stubResponse(503), stubResponse(503));
        RetryInterceptor interceptor = new RetryInterceptor("payment", props,
                new RetryBackoff(props, () -> 0.0), registry, recordingSleeper);

        interceptor.intercept(stubRequest(), new byte[0], exec);

        assertThat(sleeps).containsExactly(100L, 200L);
    }

    // --- helpers ---

    private static HttpRequest stubRequest() {
        return new HttpRequest() {
            private final HttpHeaders headers = new HttpHeaders();
            private final java.util.Map<String, Object> attributes = new java.util.HashMap<>();
            @Override public HttpMethod getMethod() { return HttpMethod.POST; }
            @Override public URI getURI() { return URI.create("http://example/test"); }
            @Override public HttpHeaders getHeaders() { return headers; }
            @Override public java.util.Map<String, Object> getAttributes() { return attributes; }
        };
    }

    private static ClientHttpResponse stubResponse(int status) {
        return new ClientHttpResponse() {
            @Override public HttpStatusCode getStatusCode() { return HttpStatusCode.valueOf(status); }
            @Override public String getStatusText() { return "" + status; }
            @Override public void close() {}
            @Override public InputStream getBody() { return new ByteArrayInputStream(new byte[0]); }
            @Override public HttpHeaders getHeaders() { return new HttpHeaders(); }
        };
    }

    /** Mockito 없이도 충분 — 응답 sequence 만 제공하는 simple stub. */
    private static class StubExecution implements ClientHttpRequestExecution {
        private final Deque<Step> steps;
        private final AtomicInteger calls = new AtomicInteger();

        private StubExecution(Deque<Step> steps) { this.steps = steps; }

        static StubExecution with(ClientHttpResponse... responses) {
            Deque<Step> q = new ArrayDeque<>();
            for (ClientHttpResponse r : responses) {
                q.add(() -> r);
            }
            return new StubExecution(q);
        }

        static StubExecution withSequence(Step... steps) {
            Deque<Step> q = new ArrayDeque<>();
            for (Step s : steps) q.add(s);
            return new StubExecution(q);
        }

        int calls() { return calls.get(); }

        @Override
        public ClientHttpResponse execute(HttpRequest request, byte[] body) throws IOException {
            calls.incrementAndGet();
            Step s = steps.poll();
            if (s == null) {
                throw new IllegalStateException("StubExecution exhausted at call #" + calls.get());
            }
            return s.run();
        }

        interface Step {
            ClientHttpResponse run() throws IOException;
        }
    }
}
