package io.minishop.correlation;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 필터 단위 테스트 — 활성 Span 의 trace_id / span_id 가 doFilter 가 호출되는 중에는 MDC 에
 * 들어가 있고, 요청 종료 후엔 정리되어야 한다.
 */
class CorrelationMdcFilterTests {

    private OpenTelemetrySdk otel;
    private InMemorySpanExporter exporter;
    private Tracer tracer;
    private CorrelationMdcFilter filter;

    @BeforeEach
    void setUp() {
        // 테스트 격리 — 이전 테스트가 남긴 MDC 가 있을 수 있어 비우고 시작.
        MDC.clear();
        exporter = InMemorySpanExporter.create();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        otel = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
        tracer = otel.getTracer("test");
        filter = new CorrelationMdcFilter(
                new CorrelationMdcProperties(true, "trace_id", "span_id"));
    }

    @AfterEach
    void tearDown() {
        otel.close();
        MDC.clear();
    }

    @Test
    void populatesMdcDuringRequestAndClearsAfter() throws Exception {
        AtomicReference<String> traceIdInsideChain = new AtomicReference<>();
        AtomicReference<String> spanIdInsideChain = new AtomicReference<>();

        Span span = tracer.spanBuilder("op").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            FilterChain chain = (req, res) -> {
                traceIdInsideChain.set(MDC.get("trace_id"));
                spanIdInsideChain.set(MDC.get("span_id"));
            };
            filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);
        } finally {
            span.end();
        }

        // doFilter 가 도는 동안엔 MDC 에 active span 의 ID 가 들어있어야.
        assertThat(traceIdInsideChain.get()).isEqualTo(span.getSpanContext().getTraceId());
        assertThat(spanIdInsideChain.get()).isEqualTo(span.getSpanContext().getSpanId());
        // 요청 종료 후엔 MDC 가 깨끗해야 — 다음 요청에 누수되지 않게.
        assertThat(MDC.get("trace_id")).isNull();
        assertThat(MDC.get("span_id")).isNull();
    }

    @Test
    void leavesMdcAloneWhenNoActiveSpan() throws Exception {
        AtomicReference<String> traceIdInsideChain = new AtomicReference<>();
        FilterChain chain = (req, res) -> traceIdInsideChain.set(MDC.get("trace_id"));

        // 활성 Span 없이 호출.
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        // 비활성 Span 환경에서도 안전하게 통과 — MDC 는 set 도 안 되고, NPE 도 없어야.
        assertThat(traceIdInsideChain.get()).isNull();
        assertThat(MDC.get("trace_id")).isNull();
    }

    @Test
    void doesNotEvictPreexistingMdcKeys() throws Exception {
        // 이 필터가 다루는 키는 trace_id / span_id 만 — 외부에서 미리 넣어둔 다른 키 (예: userId)
        // 는 doFilter 통과 후에도 그대로 남아 있어야.
        MDC.put("userId", "u-42");
        try {
            Span span = tracer.spanBuilder("op").startSpan();
            try (Scope ignored = span.makeCurrent()) {
                filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                        (req, res) -> {});
            } finally {
                span.end();
            }
            assertThat(MDC.get("userId")).isEqualTo("u-42");
        } finally {
            MDC.remove("userId");
        }
    }

    @Test
    void clearsMdcEvenWhenChainThrows() {
        Span span = tracer.spanBuilder("op").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            FilterChain throwing = (req, res) -> { throw new IllegalStateException("boom"); };
            try {
                filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), throwing);
            } catch (Exception ignoredEx) {
                // 의도된 예외.
            }
        } finally {
            span.end();
        }
        // 예외가 도중에 터져도 finally 블록의 MDC.remove 가 동작해야.
        assertThat(MDC.get("trace_id")).isNull();
        assertThat(MDC.get("span_id")).isNull();
    }

    @Test
    void respectsCustomMdcKeys() throws Exception {
        CorrelationMdcFilter customFilter = new CorrelationMdcFilter(
                new CorrelationMdcProperties(true, "tid", "sid"));

        AtomicReference<String> tid = new AtomicReference<>();
        AtomicReference<String> sid = new AtomicReference<>();

        Span span = tracer.spanBuilder("op").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            customFilter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                    (req, res) -> {
                        tid.set(MDC.get("tid"));
                        sid.set(MDC.get("sid"));
                    });
        } finally {
            span.end();
        }

        assertThat(tid.get()).isEqualTo(span.getSpanContext().getTraceId());
        assertThat(sid.get()).isEqualTo(span.getSpanContext().getSpanId());
        // 기본 키 (trace_id) 는 사용되지 않아야.
        assertThat(MDC.get("trace_id")).isNull();
    }
}
