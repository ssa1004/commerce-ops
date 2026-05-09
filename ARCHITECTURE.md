# Architecture

이 문서는 시스템이 어떻게 생겼고, 데이터가 어떻게 흐르고, 왜 그렇게 만들었는지를 한 페이지에서 보여줍니다.

> 한 줄 요약: 작은 이커머스 3개 서비스를 동기 REST로 묶고, 모든 상태 변화를 Kafka 이벤트로도 발행해, OpenTelemetry로 그 흐름을 끝까지 추적할 수 있게 만든 구조입니다.

## 컴포넌트 한눈에 보기

### 서비스 (`services/`)

| Service | 무엇을 하나 | 핵심 의존성 |
|---|---|---|
| **order-service** | 주문 입구. 결제·재고를 오케스트레이션 (여러 서비스를 직접 지휘) 하고, Order의 모든 상태 변화를 Outbox 테이블에 기록해 `order.events` 토픽으로 발행 | PostgreSQL · Kafka producer (Outbox 폴러 경유) |
| **payment-service** | 외부 PG (Payment Gateway — 결제사) mock을 호출해 결제 처리, `payment.events` 발행 (DB 커밋 직후) | PostgreSQL · Kafka producer |
| **inventory-service** | Redisson 분산락 + JPA `@Version` (낙관적 락 — 같은 행을 두 트랜잭션이 동시에 바꾸면 뒤늦은 쪽이 실패) 으로 재고를 조정, `inventory.events` 발행 | PostgreSQL · Redis · Kafka producer |

### 데이터 저장소

- **PostgreSQL** — 서비스별 독립 DB (`orderdb`, `paymentdb`, `inventorydb`)
- **Redis** — `inventory-service`의 분산락 (Redisson)
- **Kafka** — 서비스 간 비동기 이벤트 채널 (메시지 브로커 — 한 서비스가 발행한 메시지를 여러 소비자가 시간차 두고 받아갈 수 있게 해줌)
  - 토픽 (메시지 채널 이름): `order.events`, `payment.events`, `inventory.events`
  - 이벤트: `OrderCreated / OrderPaid / OrderFailed`, `PaymentSucceeded / PaymentFailed`, `InventoryReserved / InventoryReleased`
  - producer 설정: idempotent (같은 메시지가 재시도로 두 번 들어가도 한 번만 저장됨) + acks=all (모든 복제본이 받았을 때만 성공으로 본다 — 가장 안전) (자세한 이유는 [ADR-010](docs/decision-log.md))
  - 발행 보장: order는 Outbox 패턴 (DB 변경과 같은 트랜잭션 안에 이벤트 행을 기록 → 폴러가 발행), payment/inventory는 afterCommit publish (트랜잭션 커밋 직후 발행 — DB는 바뀌었는데 발행을 못 보낼 위험 약간 존재) (자세한 trade-off는 [ADR-009](docs/decision-log.md))

### 옵저버빌리티 스택 (`infra/`)

OpenTelemetry Collector (텔레메트리 신호를 한 곳에서 받아 적절한 백엔드로 라우팅하는 중간 게이트웨이) 를 단일 수신부로 두고, 신호별로 적절한 백엔드로 분기합니다.

| 신호 | 경로 |
|---|---|
| **메트릭** | Spring Micrometer → Prometheus scrape (Prometheus가 주기적으로 앱 엔드포인트를 긁어오는 pull 방식 / OTel 메트릭 export 는 끔, [ADR-008](docs/decision-log.md)) |
| **트레이스** | OTel auto-instrumentation → Collector → Tempo |
| **로그** | Logback OTel appender (Logback이 찍은 로그를 OTel 포맷으로 보내는 어댑터) → Collector → Loki |
| **시각화/알람** | Grafana (대시보드·datasource 자동 프로비저닝 — 컨테이너 기동 시 yaml 로 자동 설정) + Alertmanager (Prometheus 알람을 받아 슬랙/이메일 등으로 라우팅) |

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

`order-service`는 실패 종류별로 다른 HTTP 상태와 `X-Order-Outcome` 헤더를 돌려줍니다. 4xx는 비즈니스 결과(SLO 위반으로 카운트 안 함), 5xx는 의존성 장애.

| 시나리오 | HTTP | `X-Order-Outcome` | 보상 |
|---|---|---|---|
| 결제 거절 | 402 | `PAYMENT_DECLINED` | 앞서 잡아둔 재고 모두 해제 |
| 재고 부족 | 409 | `OUT_OF_STOCK` | 이미 잡아둔 항목만 해제 |
| 결제 서비스 장애 | 502 | `PAYMENT_INFRA` | 앞서 잡아둔 재고 모두 해제 |
| 재고 서비스 장애 | 503 | `INVENTORY_INFRA` | 가능한 만큼 해제 |

> 동기 호출의 본질적인 함정 한 가지는 [case-studies/2026-05-07-payment-timeout-race.md](case-studies/2026-05-07-payment-timeout-race.md)에 회고로 정리되어 있습니다. 호출자/피호출자의 timeout 이 같으면 in-doubt (호출자는 끊겼는데 피호출자는 작업을 끝내버려 결과를 알 수 없는) 윈도우가 생기는 문제.

### 향후: 완전 비동기 choreography (Phase 2 Step 3b)

choreography (안무) 는 어느 한 서비스가 흐름을 지휘하지 않고, 각 서비스가 이벤트를 듣고 자기 일을 한 뒤 다음 이벤트를 발행하는 방식입니다. POST /orders 가 202 Accepted (요청은 받았고 비동기로 처리한다) 를 즉시 반환하고, 이후 흐름은 Kafka 이벤트로만 진행되는 형태. 위 케이스 스터디에서 발견한 timeout 함정을 구조적으로 회피하는 것이 도입 동기입니다.

---

## 자체 운영 라이브러리 (Phase 3, `modules/`)

운영 노하우를 작은 Spring Boot starter들로 떼어내 모든 서비스에 의존성으로 추가합니다. 2개 구현 완료 (services/* 가 composite build 로 직접 사용), 3개는 설계 단계.

| 모듈 | 상태 | 역할 |
|---|---|---|
| `slow-query-detector` | ✅ v0.1 (order-service 적용) | JPA/JDBC 슬로우·N+1 자동 감지 → 메트릭/로그 (ADR-012) |
| `jfr-recorder-starter` | ✅ v0.1 (order-service 적용) | JFR continuous profiling 상시 가동 + chunk 원격 업로드 (ADR-015 / ADR-018) |
| `correlation-mdc-starter` | 📝 설계 | OTel trace_id ↔ MDC 자동 동기화 (지금은 OTel starter가 자동 처리, 추후 더 풍부한 attribute 추가) |
| `actuator-extras` | 📝 설계 | HikariCP / 스레드풀 / 트랜잭션 통계 커스텀 endpoint |
| `chaos-injector` | 📝 설계 | 메서드 단위 지연/실패 주입 (테스트·데모용) |

---

## 설계 결정 (요약)

자세한 배경은 [docs/decision-log.md](docs/decision-log.md)에 ADR로 기록 (현재 22개). 핵심:

- **Java 21 + Spring Boot 3.5** — Virtual Threads (운영체제 스레드보다 훨씬 가벼운 JVM 차원의 스레드 — 동시성 비용을 크게 낮춤) 등 신규 문법 활용
- **서비스별 DB 분리** — 마이크로서비스 원칙 + 독립 진화 (한 서비스의 스키마 변경이 다른 서비스를 막지 않게)
- **OTel 표준 채택** — 벤더 락인 (특정 도구에 코드가 묶이는 상태) 회피, 트레이스/로그만 OTel 경로 (메트릭은 Prometheus 가 직접 긁어옴)
- **Tempo (vs Jaeger)** — Grafana 스택 통합 + S3 호환 스토리지 (저비용 객체 저장소를 트레이스 백엔드로 사용)
- **OTel Spring Boot starter (vs Java agent)** — agent (JVM 시작 시 `-javaagent=...` 로 붙여 코드 변경 없이 모든 클래스를 후킹하는 방식) 는 운영 표준이지만 데모 환경엔 starter가 더 친화적. K8s 단계에서 agent 옵션 재검토
- **Outbox는 order만** — payment/inventory 는 트랜잭션 커밋 직후 발행, 운영 비용 trade-off 명시
- **Kafka transactions (read-process-write 를 원자적으로 묶는 기능) 대신 idempotent producer + 도메인 멱등키** — 운영 단순함 우선
