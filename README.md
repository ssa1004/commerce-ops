# mini-shop-observability

작은 이커머스 마이크로서비스(주문/결제/재고)를 직접 운영하면서, 그 위에 **풀 옵저버빌리티 스택**, **자체 Spring Boot 운영 라이브러리**, **장애 분석 회고**를 함께 쌓아가는 포트폴리오 프로젝트입니다.

> 코드만 보여주는 레포가 아니라, **운영자 관점에서 무엇을 보고 어떻게 판단했는지** 까지 드러나도록 만들고 있습니다.

## 핵심 데모 — 90초 안에 보여주고 싶은 것

`POST /orders` 한 번을 던지면 — `order → inventory.reserve → payment.charge → mock-pg`까지 4개 서비스를 거치는 흐름이, **하나의 trace** 안에 모든 span으로 묶여 Grafana에 그려집니다. trace에서 span을 누르면 같은 `trace_id`의 로그로 점프하고, 결제가 실패하면 자동 보상으로 재고가 복구되며, 응답 헤더 `X-Order-Outcome`이 정확히 어떤 종류의 실패인지 알려줍니다.

뒤에서는 5개의 알람 룰이 SLO를 지키고 있고, 알람마다 [런북](docs/runbook/)이 "지금 어디부터 보면 됩니다"를 안내합니다. 실제로 카오스를 주입해 발견한 첫 부정합 사례는 [case-studies/](case-studies/)에 회고로 남아 있습니다.

## 무엇이 들어있나

- **3개 마이크로서비스** (Spring Boot 3.5 / Java 21): `order` → `payment` → `inventory`, 동기 REST + SAGA 보상
- **Outbox 패턴** (order-service): Aggregate 변경과 같은 트랜잭션에 이벤트 행을 기록, 별도 폴러가 Kafka로 발행 (`SELECT … FOR UPDATE SKIP LOCKED`)
- **풀 옵저버빌리티 스택**: OpenTelemetry → Prometheus(메트릭) + Loki(로그) + Tempo(트레이스) → Grafana, trace ↔ log 양방향 점프 데이터소스 자동 프로비저닝
- **5개 알람 + 런북**: p99 latency, 5xx 비율, HikariCP 포화, GC pause, 분산락 timeout — 각각 발화 조건/영향/진단 흐름/완화책/post-mortem 가이드
- **장애 분석 회고**: 카오스 주입 → trace 분석 → 진짜 부정합을 잡아낸 첫 케이스

전체 구조와 설계 결정의 *왜*는 [ARCHITECTURE.md](ARCHITECTURE.md) / [docs/decision-log.md](docs/decision-log.md) 참고.

---

## System Overview

```
┌──────────┐     ┌──────────┐     ┌────────────┐
│  order   │ ──▶ │ payment  │     │ inventory  │
│ service  │     │ service  │     │  service   │
└────┬─────┘     └────┬─────┘     └─────┬──────┘
     │  Kafka events (order/payment/inventory.events)
     ├────────────────┴─────────────────┤
     │                                  │
┌────▼──────────────────────────────────▼────┐
│           OpenTelemetry Collector          │
└────┬──────────────┬──────────────┬─────────┘
     │              │              │
┌────▼─────┐  ┌─────▼────┐  ┌──────▼─────┐
│Prometheus│  │   Loki   │  │   Tempo    │
└────┬─────┘  └─────┬────┘  └──────┬─────┘
     └──────────────┼──────────────┘
                    ▼
              ┌──────────┐
              │ Grafana  │
              └──────────┘
```

## 진행 상황

| Phase | 상태 |
|---|---|
| **Phase 0** — 레포 스캐폴드 + 인프라 설정 | ✅ |
| **Phase 1** — 3개 서비스 + 동기 SAGA + JVM/HTTP 대시보드 | ✅ |
| **Phase 2** — OTel 자동계측, Tempo/Loki, 알람 5개, Outbox 패턴, 첫 케이스 스터디 | ✅ (Step 3b만 남음) |
| **Phase 3** — 자체 Spring Boot 운영 라이브러리 (`modules/`) | 설계 단계 |
| **Phase 4** — 카오스 시나리오 누적 + 케이스 스터디 누적 (지속) | 진행 중 |

자세한 단계는 [ROADMAP.md](ROADMAP.md) 참고.

---

## Quick Start

### 1. 인프라 띄우기

```bash
docker compose -f infra/docker-compose.yml up -d
```

Postgres, Redis, Kafka, Prometheus, Loki, Tempo, Grafana, Alertmanager가 한 번에 올라옵니다. Grafana는 데이터소스와 대시보드까지 자동 프로비저닝됩니다.

| URL | 용도 |
|---|---|
| http://localhost:3000 | Grafana — `admin / admin` |
| http://localhost:9090 | Prometheus (`/alerts`에서 발화 중인 알람 확인) |
| http://localhost:3200 | Tempo |
| http://localhost:9093 | Alertmanager |

설정만 검증하려면: `docker compose -f infra/docker-compose.yml config`

### 2. 서비스 3개 실행

```bash
# 각각 다른 셸에서
cd services/order-service     && ./gradlew bootRun   # :8081
cd services/payment-service   && ./gradlew bootRun   # :8082
cd services/inventory-service && ./gradlew bootRun   # :8083
```

JDK 21이 없어도 됩니다 — Gradle 툴체인이 foojay에서 자동 다운로드합니다.

### 3. 흐름 직접 만져보기

```bash
# 정상 — 201 Created, status=PAID
curl -s -X POST localhost:8081/orders \
  -H 'Content-Type: application/json' \
  -d '{"userId":42,"items":[{"productId":1001,"quantity":2,"price":9990}]}' | jq

# 결제 항상 실패 시뮬레이션 — 402 PAYMENT_DECLINED + 재고 자동 복구
# (payment-service를 MOCK_PG_FAILURE_RATE=1.0 으로 재시작한 뒤)
curl -i -X POST localhost:8081/orders \
  -H 'Content-Type: application/json' \
  -d '{"userId":42,"items":[{"productId":1001,"quantity":1,"price":9990}]}'

# 재고 부족 — 409 OUT_OF_STOCK + 앞에서 reserve된 다른 아이템 자동 복구
curl -i -X POST localhost:8081/orders \
  -H 'Content-Type: application/json' \
  -d '{"userId":42,"items":[{"productId":1003,"quantity":9999,"price":9990}]}'
```

응답 헤더 `X-Order-Outcome`에 `OUT_OF_STOCK / PAYMENT_DECLINED / INVENTORY_INFRA / PAYMENT_INFRA` 분류가 실립니다 — 클라이언트가 5xx 디버깅 없이 어디서 막혔는지 즉답.

### 4. 부하 테스트 (선택)

```bash
k6 run load/baseline.js
```

### 5. Grafana에서 메트릭 → 로그 → 트레이스 점프

OpenTelemetry가 3개 서비스를 자동 계측하고 있어, 한 요청을 trace 하나로 묶어 보여줍니다.

- **대시보드** — Grafana → Dashboards → **JVM + HTTP** (3개 서비스 통합)
- **트레이스** — Grafana → Explore → **Tempo** → 최근 trace 클릭. order/inventory/payment/mock-pg 5~7개 span이 한 그림.
- **로그** — Tempo span에서 *"Logs for this span"* 클릭하면 Loki에서 같은 `trace_id`만 자동 필터. 역방향도 가능.
- **콘솔** — 서비스 stdout에도 `[trace_id/span_id]`가 인라인으로 찍혀 grep 가능.

---

## 추천 읽는 순서

5분 정도로 의도를 파악하고 싶다면:

1. **여기 README 상단** — 뭘 보여주려는 레포인지
2. [ARCHITECTURE.md](ARCHITECTURE.md) — 컴포넌트와 데이터 흐름
3. [docs/decision-log.md](docs/decision-log.md) — 어떤 trade-off를 받아들였는지 (10개 ADR)
4. [case-studies/](case-studies/) — 실제로 무엇을 발견했는지
5. [docs/runbook/](docs/runbook/) — 알람이 떴을 때의 대응 절차

서비스/인프라 코드를 깊게 보려면 각 디렉토리의 README:
- [services/order-service/README.md](services/order-service/README.md) — SAGA orchestration + Outbox
- [services/inventory-service/README.md](services/inventory-service/README.md) — Redisson 분산락 + 멱등성
- [services/payment-service/README.md](services/payment-service/README.md) — mock PG + afterCommit publish
- [infra/README.md](infra/README.md) — 옵저버빌리티 스택 운영 가이드
- [modules/README.md](modules/README.md) — Phase 3 자체 라이브러리 설계

---

## Repository Layout

```
mini-shop-observability/
├── README.md / ARCHITECTURE.md / ROADMAP.md
├── docs/
│   ├── decision-log.md      # ADR 10개
│   ├── runbook/             # 알람별 대응 절차
│   └── slo.md
├── services/
│   ├── order-service/       # 주문 + SAGA + Outbox
│   ├── payment-service/     # 결제 + mock-pg
│   └── inventory-service/   # 재고 + 분산락
├── modules/                 # Phase 3: 자체 Spring Boot 운영 라이브러리 (설계 단계)
├── infra/                   # docker-compose + Prometheus/Grafana/Loki/Tempo/Alertmanager 설정
├── load/                    # k6 시나리오
├── chaos/                   # 장애 주입 시나리오
├── case-studies/            # 실제 부딪힌 케이스 회고
└── .github/workflows/       # CI: docker compose / promtool / amtool / Gradle build
```

---

## License

MIT
