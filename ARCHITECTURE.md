# Architecture

이 문서는 최종 목표 구조를 설명합니다. 현재 저장소는 Phase 0 스캐폴드와 인프라 설정 단계이며, 서비스 구현은 [ROADMAP.md](ROADMAP.md)의 Phase 1부터 진행합니다.

## Components

### Target Services

| Service | 책임 | 주요 의존성 |
|---|---|---|
| `order-service` | 주문 생성/조회, 결제·재고 오케스트레이션 | PostgreSQL, Kafka producer |
| `payment-service` | 결제 처리 (외부 PG 호출 시뮬레이션), 결제 결과 이벤트 발행 | PostgreSQL, Kafka, 외부 API mock |
| `inventory-service` | 재고 차감/복구, 재고 캐싱 | PostgreSQL, Redis, Kafka consumer |

### Data Stores

- **PostgreSQL** — 서비스별 독립 스키마 (per-service DB pattern)
- **Redis** — `inventory-service` 캐시 + 분산락 (재고 동시성 데모)
- **Kafka** — 서비스 간 비동기 이벤트 (`OrderCreated`, `PaymentSucceeded`, `InventoryReserved`)

### Observability Stack

- **OpenTelemetry Collector** — 메트릭/로그/트레이스 수신부
- **Prometheus** — 메트릭 저장 + 쿼리
- **Loki** — 로그 저장 + 쿼리 (구조화 로그 + Trace ID 상관관계)
- **Tempo** — 분산 트레이스 저장
- **Grafana** — 통합 시각화 + 알람 룰
- **Alertmanager** — 알람 라우팅

---

## Data Flow

Phase 1에서는 구현 난이도를 낮추기 위해 `order-service`가 `payment-service`, `inventory-service`를 동기 REST로 호출합니다. 아래 흐름은 Phase 2에서 Kafka 이벤트 기반으로 전환한 뒤의 목표 구조입니다.

### 주문 생성 Happy Path

```
Client
  │ POST /orders
  ▼
order-service
  │ ① 주문 INSERT (PG)
  │ ② Kafka publish: OrderCreated
  ▼
payment-service (consumer)
  │ ③ 외부 PG 호출 (mock)
  │ ④ Kafka publish: PaymentSucceeded
  ▼
order-service (consumer)
  │ ⑤ 주문 상태 업데이트
inventory-service (consumer of OrderCreated)
  │ ⑥ Redis 분산락 → 재고 차감 → DB 반영
  │ ⑦ Kafka publish: InventoryReserved
```

이 흐름은 분산 트레이싱이 가장 잘 보이는 시나리오로, Tempo 데모의 메인 케이스가 됩니다.

### 실패 시나리오 (의도적으로 보여줄 케이스)

- 결제 실패 → SAGA 보상 (재고 복구)
- 재고 부족 → 주문 취소
- 서비스 다운 → Kafka 메시지 적재 + 복구 후 처리
- DB 커넥션 고갈 → HikariCP 메트릭 + 알람
- 외부 PG 지연 → p99 튐 → trace로 원인 식별

---

## Custom Modules

`modules/` 아래의 자체 라이브러리들이 모든 서비스에 적용됩니다. 자세한 설계는 각 모듈 README 참고.

| 모듈 | 역할 |
|---|---|
| `slow-query-detector` | JPA/JDBC 슬로우·N+1 자동 감지 → 메트릭/로그/Trace 이벤트 |
| `correlation-mdc-starter` | Trace ID ↔ MDC 자동 연동, 로그·트레이스·메트릭 상관관계 |
| `actuator-extras` | HikariCP·스레드풀·트랜잭션 통계 커스텀 엔드포인트 |
| `chaos-injector` | 메서드 단위 지연/실패 주입 (테스트·데모용) |

---

## Decision Log

설계 결정의 *이유*는 [docs/decision-log.md](docs/decision-log.md) 참고. 주요 결정:

- Java 21 + Spring Boot 3 (Virtual Threads 활용)
- 서비스 간 통신은 동기 REST가 아닌 Kafka 비동기 이벤트 (트레이싱·SAGA·Outbox 데모 동기)
- OpenTelemetry 표준 채택 (벤더 락인 회피)
- Tempo 채택 이유 (vs Jaeger): Grafana stack 통합 + S3 호환 스토리지
- 서비스별 DB 분리 vs 단일 DB → 분리 (마이크로서비스 원칙 + 독립 진화)
