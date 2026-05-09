# correlation-mdc-starter

> 📝 **설계 단계** — `src/` 없음, 의존성으로 추가해도 동작 코드 없음. ROADMAP Phase 3 Step 6 에서 구현 예정.

OpenTelemetry **Trace ID** (요청 하나의 식별자) 를 SLF4J **MDC** (Mapped Diagnostic Context — 이 스레드의 키밸류 저장소, 로그 패턴에서 `%X{key}` 로 출력 가능) 에 자동 주입. 로그·트레이스·메트릭 상관관계가 단일 ID 로 연결되도록.

> ℹ️ **현재 상태와의 비교**: OTel Spring Boot starter (services/* 가 이미 사용 중) 가 trace_id / span_id 는 자동으로 MDC 에 넣어준다. 본 모듈의 정식 도입 동기는 *비즈니스 attribute* (X-User-Id, X-Request-Id 등 헤더 → 마스킹 → MDC) 의 일관 처리. ADR-013 의 PII 마스킹 정책을 starter 차원에서 강제하는 자리.

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
