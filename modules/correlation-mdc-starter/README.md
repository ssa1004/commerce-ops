# correlation-mdc-starter

OpenTelemetry **Trace ID** (요청 하나의 식별자) 를 SLF4J **MDC** (Mapped Diagnostic Context — 이 스레드의 키밸류 저장소, 로그 패턴에서 `%X{key}` 로 출력 가능) 에 자동 주입. 로그·트레이스·메트릭 상관관계가 단일 ID 로 연결되도록.

## 배경

- OTel auto-instrumentation 이 trace ID 는 만들어주지만, 로그 패턴에 자동으로 들어가진 않음
- 매 서비스에서 `MDCInsertingServletFilter` 같은 boilerplate (반복적인 정형 코드) 가 반복
- → 의존성만 추가하면 끝나도록

## 동작 방식 (계획)

- `OpenTelemetry` SDK 의 현재 Span 에서 Trace ID/Span ID 를 읽어 MDC 에 주입
- 동기 흐름: ServletFilter (Spring MVC) / WebFilter (WebFlux — 리액티브 스택) 분기
- 비동기 흐름: `Executor` 데코레이터 (다른 스레드로 작업이 넘어갈 때 MDC 가 따라가게) + Reactor `ContextRegistry` (리액티브 컨텍스트 전파 도구)
- Kafka consumer: `RecordInterceptor` 로 헤더의 traceparent (W3C 표준 trace 컨텍스트 헤더) → MDC

## Configuration (예시)

```yaml
mini-shop:
  correlation:
    enabled: true
    mdc-keys:
      trace-id: traceId
      span-id: spanId
      user-id: userId         # X-User-Id 헤더에서 추출
```

## Logback 패턴 변경 가이드

```
%d{ISO8601} [%X{traceId:-}] [%X{userId:-}] %-5level %logger{36} - %msg%n
```

→ Loki/Grafana 에서 traceId 로 검색 → Tempo trace 로 한 번에 점프 가능.

## TODO

- [ ] AutoConfiguration
- [ ] WebFlux 분기
- [ ] Kafka Consumer 인터셉터
- [ ] 비동기 Executor 데코레이터
- [ ] DESIGN.md (Reactor Context 전파 방식 설명)
