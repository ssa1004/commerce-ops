# correlation-mdc-starter

OpenTelemetry **Trace ID**를 SLF4J **MDC**에 자동 주입. 로그·트레이스·메트릭 상관관계가 단일 ID로 연결되도록.

## 왜 만드나

- OTel auto-instrumentation이 trace ID는 만들어주지만, 로그 패턴에 자동으로 들어가진 않음
- 매 서비스에서 `MDCInsertingServletFilter` 같은 보일러플레이트 반복
- → 의존성만 추가하면 끝나도록

## 동작 방식 (계획)

- `OpenTelemetry` SDK의 현재 Span에서 Trace ID/Span ID를 읽어 MDC에 주입
- 동기 흐름: ServletFilter / WebFilter (WebFlux 분기)
- 비동기 흐름: `Executor` 데코레이터 + Reactor `ContextRegistry`
- Kafka consumer: `RecordInterceptor`로 헤더의 traceparent → MDC

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

→ Loki/Grafana에서 traceId로 검색 → Tempo trace로 한 번에 점프 가능.

## TODO

- [ ] AutoConfiguration
- [ ] WebFlux 분기
- [ ] Kafka Consumer 인터셉터
- [ ] 비동기 Executor 데코레이터
- [ ] DESIGN.md (Reactor Context 전파 방식 설명)
