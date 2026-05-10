# correlation-mdc-starter

OpenTelemetry **Trace ID / Span ID** 를 SLF4J **MDC** (Mapped Diagnostic Context — 이 스레드의 키밸류 저장소, 로그 패턴에서 `%X{key}` 로 출력 가능) 에 자동 주입. 로그·트레이스가 같은 ID 로 연결되도록.

> ✅ **v0.1 — 최소 동작 (Servlet 한정)**: 한 요청 동안 trace_id / span_id 를 MDC 에 set, 종료 시 정리. WebFlux / Kafka consumer / 비즈니스 attribute (X-User-Id 등) 는 후속 단계 — ROADMAP Phase 3 Step 6 에서 확장.

## 배경

- OTel auto-instrumentation 이 trace ID 는 만들어주지만, 로그 패턴에 자동으로 들어가진 않음
- 매 서비스에서 `MDCInsertingServletFilter` 같은 boilerplate (반복적인 정형 코드) 가 반복
- → 의존성만 추가하면 끝나도록

## 동작 (v0.1)

- Servlet `OncePerRequestFilter` — `Span.current().getSpanContext()` 가 valid 면 trace_id / span_id 를 MDC 에 set, 요청 종료 finally 에서 *우리가 set 한 키만* remove (외부에서 미리 넣어둔 다른 MDC 키는 안 건드림)
- 자동 활성: OTel API + Spring MVC (Servlet) + spring-web 조건. WebFlux / non-web 컨텍스트에서는 자동 평가 자체가 비활성
- 필터 순서: `Ordered.HIGHEST_PRECEDENCE + 10` — 다른 필터의 로그가 trace_id 를 함께 찍을 수 있도록 가장 앞쪽

## Configuration

```yaml
mini-shop:
  correlation:
    enabled: true              # 끄려면 false
    trace-id-key: trace_id     # MDC 키 이름 (기본 trace_id)
    span-id-key: span_id       # MDC 키 이름 (기본 span_id)
```

## Logback 패턴 변경 가이드

```
%d{ISO8601} [%X{trace_id:-}] [%X{span_id:-}] %-5level %logger{36} - %msg%n
```

→ Loki/Grafana 에서 trace_id 로 검색 → Tempo trace 로 한 번에 점프 가능.

## 후속 단계 (예정)

- WebFlux 분기 — `WebFilter` + Reactor Context 전파
- Kafka consumer — `RecordInterceptor` 로 헤더의 traceparent (W3C 표준 trace 컨텍스트 헤더) → MDC
- 비동기 Executor 데코레이터 — 다른 스레드로 작업이 넘어갈 때 MDC 가 따라가게
- 비즈니스 attribute (X-User-Id 등) 의 마스킹 정책 통합 (ADR-013)

## 설치 (mavenLocal 에서)

```kotlin
// build.gradle.kts
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("io.minishop:correlation-mdc-starter:0.1.0-SNAPSHOT")
}
```

의존성만 추가하면 자동 활성. 끄려면 `mini-shop.correlation.enabled=false`.
