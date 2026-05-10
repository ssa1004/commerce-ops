package io.minishop.correlation;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 요청 단위로 OpenTelemetry 의 현재 Span 에서 trace_id / span_id 를 읽어 SLF4J MDC 에 넣고,
 * 요청 종료 시 정리한다.
 *
 * <h2>왜 필요한가</h2>
 * <p>OTel Spring Boot starter 가 trace 자체는 만들어 주지만, 로그에 trace_id 를 같이 찍는 건
 * 여전히 사용자 책임이다. 보통 logback 패턴이 {@code %X{trace_id}} 를 참조하는데, 그 키를 MDC 에
 * 넣어주는 코드가 없으면 비어 있다 (`%X{trace_id:-}` → 공백). 같은 사고를 추적할 때 Loki ↔ Tempo
 * 점프가 깨진다.
 *
 * <h2>동작</h2>
 * <ol>
 *   <li>{@link Span#current()} 으로 현재 활성 Span 의 {@link SpanContext} 를 가져온다 — OTel API
 *       agent / starter 가 이미 컨텍스트를 활성화 해둔 상태.</li>
 *   <li>{@code SpanContext.isValid()} 이면 trace_id / span_id 를 MDC 에 set.</li>
 *   <li>요청 종료 (정상/예외 무관) finally 에서 *우리가 set 한 키만* MDC.remove — 외부에서 미리
 *       넣어둔 다른 MDC 키를 건드리지 않게.</li>
 * </ol>
 *
 * <h2>세부 결정</h2>
 * <ul>
 *   <li><b>set 했는지 기억해 두고 그 키만 remove</b> — Span 이 invalid 일 땐 set 하지 않으므로
 *       remove 도 하지 않는다. {@link MDC#remove(String)} 는 안전하지만, "이 필터가 직접 다룬 키"
 *       만 명확히 책임지면 다음 필터/인터셉터 동작이 예측 가능해진다.</li>
 *   <li><b>비활성 OTel 환경에서 안전</b> — Span.current() 는 활성 컨텍스트가 없으면
 *       Span.invalid() 를 돌려준다. 추가 가드 없이 isValid() 한 줄로 처리.</li>
 *   <li><b>OncePerRequestFilter 사용</b> — forward / include 로 같은 요청이 두 번 들어와도
 *       MDC 가 두 번 set 되지 않는다.</li>
 * </ul>
 */
public class CorrelationMdcFilter extends OncePerRequestFilter {

    private final CorrelationMdcProperties props;

    public CorrelationMdcFilter(CorrelationMdcProperties props) {
        this.props = props;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        boolean traceIdSet = false;
        boolean spanIdSet = false;
        try {
            SpanContext ctx = Span.current().getSpanContext();
            if (ctx.isValid()) {
                MDC.put(props.traceIdKey(), ctx.getTraceId());
                MDC.put(props.spanIdKey(), ctx.getSpanId());
                traceIdSet = true;
                spanIdSet = true;
            }
            filterChain.doFilter(request, response);
        } finally {
            if (traceIdSet) MDC.remove(props.traceIdKey());
            if (spanIdSet) MDC.remove(props.spanIdKey());
        }
    }
}
