# mini-shop-observability

> 작은 이커머스 마이크로서비스를 직접 운영하면서 **관측성 인프라**, **Spring Boot 운영 라이브러리**, **장애 분석 케이스 스터디**를 함께 쌓는 포트폴리오 프로젝트.

이 레포의 목적은 두 가지입니다.

1. **포트폴리오** — 서비스 코드뿐 아니라 운영 설계, 관측성, 알람, 장애 대응 문서까지 한 저장소에서 보여준다.
2. **개인 학습** — 백엔드와 DevOps 역량을 "실제 운영에서 무엇을 보고 어떻게 판단할 것인가"의 형태로 축적한다.

## Current Status

**Phase 1 (MVP) 완료** — 3개 서비스가 동기 REST로 묶여 happy/실패 경로 모두 동작.

| 영역 | 현재 상태 |
|---|---|
| Docker Compose infra | PostgreSQL, Redis, Kafka, Prometheus, Loki, Tempo, Grafana, Alertmanager |
| Grafana dashboards | Infra overview + JVM+HTTP (3개 서비스 통합 view) 자동 프로비저닝 |
| Services | order / payment / inventory 모두 Spring Boot 3.5 + Java 21, Testcontainers 통합 테스트 포함 |
| Order orchestration | reserve → pay → on-failure compensate (SAGA 동기 버전), `X-Order-Outcome` 헤더로 분류 |
| Idempotency | inventory-service `(orderId, productId)` 단위 reserve/release 멱등 |
| Distributed lock | Redisson 분산락 + JPA `@Version` 이중 안전망, `inventory_lock_acquire_seconds` 메트릭 |
| Load test | k6 baseline (`POST /orders` happy path 시뮬레이션) |
| Custom modules | Phase 3 (설계 문서 단계) |
| Case studies | 운영 회고 템플릿 준비, Phase 2 이후부터 실제 케이스 누적 |

---

## System Overview

```
┌──────────┐     ┌──────────┐     ┌────────────┐
│  order   │ ──▶ │ payment  │     │ inventory  │
│ service  │     │ service  │     │  service   │
└────┬─────┘     └────┬─────┘     └─────┬──────┘
     │                │                 │
     │   Kafka events │                 │
     ├────────────────┴─────────────────┤
     │                                  │
┌────▼──────────────────────────────────▼────┐
│           OpenTelemetry Collector          │
└────┬──────────────┬──────────────┬─────────┘
     │              │              │
┌────▼─────┐  ┌─────▼────┐  ┌──────▼─────┐
│Prometheus│  │   Loki   │  │   Tempo    │
└────┬─────┘  └─────┬────┘  └──────┬─────┘
     │              │              │
     └──────────────┼──────────────┘
                    ▼
              ┌──────────┐
              │ Grafana  │
              └──────────┘
```

- **Target services**: `order` / `payment` / `inventory` (Spring Boot 3, Java 21)
- **Data**: PostgreSQL (per-service), Redis, Kafka
- **Observability**: OpenTelemetry → Prometheus(metrics) + Loki(logs) + Tempo(traces) → Grafana
- **Custom modules**: `modules/` 아래의 자체 Spring Boot 운영 라이브러리들이 서비스에 적용됨
- **Load / Chaos**: k6 시나리오, 장애 주입 가이드

자세한 목표 구조와 설계 결정은 [ARCHITECTURE.md](ARCHITECTURE.md) 참고.

---

## Quick Start

### 1) 인프라 + 의존성 (Postgres / Redis / Kafka / 옵저버빌리티 스택)

```bash
docker compose -f infra/docker-compose.yml up -d
```

| URL | 용도 |
|---|---|
| http://localhost:3000 | Grafana (admin / admin) — JVM+HTTP 대시보드 자동 프로비저닝됨 |
| http://localhost:9090 | Prometheus |
| http://localhost:3200 | Tempo |
| http://localhost:9093 | Alertmanager |

설정 파일 검증만: `docker compose -f infra/docker-compose.yml config`

### 2) 서비스 3개 실행 (각 셸에서)

```bash
cd services/order-service     && ./gradlew bootRun   # 8081
cd services/payment-service   && ./gradlew bootRun   # 8082
cd services/inventory-service && ./gradlew bootRun   # 8083
```

JDK 21 미설치라면 Gradle 툴체인(foojay-resolver)이 자동 다운로드합니다.

### 3) end-to-end 데모

```bash
# happy path → 201 PAID
curl -s -X POST localhost:8081/orders \
  -H "Content-Type: application/json" \
  -d '{"userId":42,"items":[{"productId":1001,"quantity":2,"price":9990}]}' | jq

# 결제 실패 시뮬레이션 → 402 PAYMENT_DECLINED + 재고 자동 복구
MOCK_PG_FAILURE_RATE=1.0 # payment-service 재시작 시 적용
curl -s -X POST localhost:8081/orders \
  -H "Content-Type: application/json" \
  -d '{"userId":42,"items":[{"productId":1001,"quantity":1,"price":9990}]}' -i

# 재고 부족 → 409 OUT_OF_STOCK + 이미 reserve된 다른 아이템 자동 복구
curl -s -X POST localhost:8081/orders \
  -H "Content-Type: application/json" \
  -d '{"userId":42,"items":[{"productId":1003,"quantity":9999,"price":9990}]}' -i
```

응답 헤더 `X-Order-Outcome`에 `OUT_OF_STOCK / PAYMENT_DECLINED / INVENTORY_INFRA / PAYMENT_INFRA` 분류가 들어옵니다.

### 4) 부하 테스트 (선택)

```bash
k6 run load/baseline.js
```

→ Grafana → "JVM + HTTP" 대시보드에서 3개 서비스 메트릭 동시에 보기.

---

## Repository Layout

```
mini-shop-observability/
├── README.md / ARCHITECTURE.md / ROADMAP.md
├── docs/                # decision log, SLO, runbook
├── services/            # 마이크로서비스 (order/payment/inventory)
├── modules/             # ✨ 자체 Spring Boot 운영 라이브러리
├── infra/               # docker-compose + 옵저버빌리티 스택 설정
├── load/                # k6 부하 시나리오
├── chaos/               # 장애 주입 시나리오
├── case-studies/        # ⭐ 실제 운영하며 발견한 케이스 회고
└── .github/workflows/   # CI
```

---

## Review Path

포트폴리오 검토자가 짧은 시간 안에 의도를 파악할 수 있도록 아래 순서로 문서를 배치했습니다.

1. [ARCHITECTURE.md](ARCHITECTURE.md) — 목표 시스템 구조와 주요 데이터 흐름
2. [docs/decision-log.md](docs/decision-log.md) — 기술 선택 이유
3. [infra/README.md](infra/README.md) — 현재 실행 가능한 인프라와 검증 방법
4. [modules/README.md](modules/README.md) — 직접 만들 운영 라이브러리 목록
5. [case-studies/_template.md](case-studies/_template.md) — 장애 분석을 어떻게 남길지에 대한 기준

---

## Roadmap

진행 상황은 [ROADMAP.md](ROADMAP.md) 참고. 큰 흐름:

- [x] Phase 0 — 레포 스캐폴드
- [x] Phase 1 — MVP: 3개 서비스 골격 + 동기 REST orchestration + 기본 대시보드
- [ ] Phase 2 — OTel 계측 + Loki/Tempo + 의미 있는 알람 + k6
- [ ] Phase 3 — 자체 운영 모듈 적용
- [ ] Phase 4 — 카오스 + 케이스 스터디 누적 (지속)

---

## License

MIT
