# Architecture

이 문서는 시스템이 어떻게 생겼고, 데이터가 어떻게 흐르고, 왜 그렇게 만들었는지를 한 페이지에서 보여줍니다.

> 한 줄 요약: 작은 이커머스 3개 서비스를 동기 REST로 묶고, 모든 상태 변화를 Kafka 이벤트로도 발행해, OpenTelemetry로 그 흐름을 끝까지 추적할 수 있게 만든 구조입니다.

## 컴포넌트 한눈에 보기

### 서비스 (`services/`)

| Service | 무엇을 하나 | 핵심 의존성 |
|---|---|---|
| **order-service** | 주문 입구. 결제·재고를 오케스트레이션하고, Order의 모든 상태 변화를 Outbox에 기록해 `order.events`로 발행 | PostgreSQL · Kafka producer (via outbox poller) |
| **payment-service** | 외부 PG mock을 호출해 결제 처리, `payment.events` 발행 (afterCommit) | PostgreSQL · Kafka producer |
| **inventory-service** | Redisson 분산락 + JPA `@Version`으로 재고를 조정, `inventory.events` 발행 | PostgreSQL · Redis · Kafka producer |

### 데이터 저장소

- **PostgreSQL** — 서비스별 독립 DB (`orderdb`, `paymentdb`, `inventorydb`)
- **Redis** — `inventory-service`의 분산락 (Redisson)
- **Kafka** — 서비스 간 비동기 이벤트 채널
  - 토픽: `order.events`, `payment.events`, `inventory.events`
  - 이벤트: `OrderCreated / OrderPaid / OrderFailed`, `PaymentSucceeded / PaymentFailed`, `InventoryReserved / InventoryReleased`
  - producer 설정: idempotent + acks=all (자세한 이유는 [ADR-010](docs/decision-log.md))
  - 발행 보장: order는 Outbox 패턴, payment/inventory는 afterCommit publish (자세한 trade-off는 [ADR-009](docs/decision-log.md))

### 옵저버빌리티 스택 (`infra/`)

OpenTelemetry Collector를 단일 수신부로 두고, 신호별로 적절한 백엔드로 분기합니다.

| 신호 | 경로 |
|---|---|
| **메트릭** | Spring Micrometer → Prometheus scrape (OTel 메트릭 export는 끔, [ADR-008](docs/decision-log.md)) |
| **트레이스** | OTel auto-instrumentation → Collector → Tempo |
| **로그** | Logback OTel appender → Collector → Loki |
| **시각화/알람** | Grafana (대시보드·datasource 자동 프로비저닝) + Alertmanager |

---

## 데이터 흐름

### 정상 주문 (현재 — 동기 REST + 비동기 이벤트 알림)

```
Client
  │ POST /orders
  ▼
order-service
  │ ① Order(PENDING) 저장 + Outbox에 OrderCreated 기록 (같은 트랜잭션)
  │
  │ ② inventory-service.reserve  (각 item별, 동기 REST, 멱등 키 = orderId+productId)
  │      └─ 성공 시: InventoryReserved 이벤트 발행
  │
  │ ③ payment-service.charge  (동기 REST)
  │      └─ 결과에 따라 PaymentSucceeded / PaymentFailed 이벤트 발행
  │
  │ ④ Order 상태 전이 (PAID 또는 FAILED) + Outbox에 OrderPaid/OrderFailed (같은 트랜잭션)
  │
  ▼
Outbox Poller (별도 스레드)
  │ ⑤ SELECT pending FOR UPDATE SKIP LOCKED → Kafka publish → mark SENT
```

이 흐름은 OpenTelemetry로 끝까지 한 trace로 묶여 보입니다 (`order → inventory → order → payment → mock-pg → order` 흐름의 5~7개 span). Outbox poller의 publish span도 같은 백그라운드에서 별도 trace로 남습니다.

### 실패 시나리오와 응답 매핑

`order-service`는 실패 종류별로 다른 HTTP 상태와 `X-Order-Outcome` 헤더를 돌려줍니다. 4xx는 비즈니스 결과(SLO 위반 아님), 5xx는 의존성 장애.

| 시나리오 | HTTP | `X-Order-Outcome` | 보상 |
|---|---|---|---|
| 결제 거절 | 402 | `PAYMENT_DECLINED` | 모든 reserve release |
| 재고 부족 | 409 | `OUT_OF_STOCK` | 앞서 reserve된 item만 release |
| 결제 서비스 장애 | 502 | `PAYMENT_INFRA` | 모든 reserve release |
| 재고 서비스 장애 | 503 | `INVENTORY_INFRA` | 가능한 만큼 release |

> ⚠️ 동기 호출의 본질적인 함정 한 가지는 [case-studies/2026-05-07-payment-timeout-race.md](case-studies/2026-05-07-payment-timeout-race.md)에 회고로 정리되어 있습니다. 호출자/피호출자의 timeout이 같으면 *in-doubt* 윈도우가 생기는 문제.

### 향후: 완전 비동기 choreography (Phase 2 Step 3b)

POST /orders가 202 Accepted를 즉시 반환하고, 이후 흐름은 Kafka 이벤트로만 진행되는 형태. 위 케이스 스터디에서 발견한 timeout 함정을 구조적으로 회피하는 동기 부여가 됩니다.

---

## 자체 운영 라이브러리 (Phase 3, `modules/`)

운영 노하우를 작은 Spring Boot starter들로 떼어내 모든 서비스에 의존성으로 추가하도록 만들 예정입니다. 현재는 설계 문서 단계.

| 모듈 | 역할 |
|---|---|
| `slow-query-detector` | JPA/JDBC 슬로우·N+1 자동 감지 → 메트릭/로그/trace event |
| `correlation-mdc-starter` | OTel trace_id ↔ MDC 자동 동기화 (지금은 OTel starter가 자동 처리, 추후 더 풍부한 attribute 추가) |
| `actuator-extras` | HikariCP / 스레드풀 / 트랜잭션 통계 커스텀 endpoint |
| `chaos-injector` | 메서드 단위 지연/실패 주입 (테스트·데모용) |

---

## 설계 결정 (요약)

자세한 *왜*는 [docs/decision-log.md](docs/decision-log.md)에 ADR로 기록 (현재 10개). 핵심:

- **Java 21 + Spring Boot 3.5** — Virtual Threads 등 신규 문법 활용
- **서비스별 DB 분리** — 마이크로서비스 원칙 + 독립 진화
- **OTel 표준 채택** — 벤더 락인 회피, traces/logs만 OTel 경로 (메트릭은 Prometheus scrape)
- **Tempo (vs Jaeger)** — Grafana 스택 통합 + S3 호환 스토리지
- **OTel Spring Boot starter (vs Java agent)** — 데모 친화, K8s 단계에서 agent 옵션 재검토
- **Outbox는 order만** — payment/inventory는 afterCommit publish, 운영 비용 trade-off 명시
- **Kafka transactions 대신 idempotent producer + 도메인 멱등키** — 운영 단순함 우선
