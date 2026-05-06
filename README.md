# mini-shop-observability

> 작은 이커머스 마이크로서비스를 직접 운영하면서, 그 위에 **자체 Spring Boot 운영 라이브러리**를 만들고, 발생하는 트러블을 **케이스 스터디**로 쌓는 플레이그라운드.

이 레포의 목적은 두 가지입니다.

1. **포트폴리오** — 실제로 돌아가는 마이크로서비스 + 풀 옵저버빌리티 스택을 한 줄로 띄워서 보여줄 수 있다.
2. **개인 학습** — 시니어 백엔드 + DevOps 역량을 "운영 경험"의 형태로 축적한다.

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

- **Services**: `order` / `payment` / `inventory` (Spring Boot 3, Java 21)
- **Data**: PostgreSQL (per-service), Redis, Kafka
- **Observability**: OpenTelemetry → Prometheus(metrics) + Loki(logs) + Tempo(traces) → Grafana
- **Custom modules**: `modules/` 아래의 자체 Spring Boot 운영 라이브러리들이 서비스에 적용됨
- **Load / Chaos**: k6 시나리오, 장애 주입 가이드

자세한 데이터 흐름과 설계 결정은 [ARCHITECTURE.md](ARCHITECTURE.md) 참고.

---

## Quick Start

> **TODO**: Phase 1 완료 후 한 줄 실행 가능하도록 정리

```bash
# 인프라(옵저버빌리티 스택 + 의존성) 띄우기
docker compose -f infra/docker-compose.yml up -d

# 서비스 빌드 & 실행 (Phase 1 이후)
./gradlew bootRun
```

확인할 곳:

| URL | 용도 |
|---|---|
| http://localhost:3000 | Grafana (admin / admin) |
| http://localhost:9090 | Prometheus |
| http://localhost:3200 | Tempo |
| http://localhost:9093 | Alertmanager |

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

## Roadmap

진행 상황은 [ROADMAP.md](ROADMAP.md) 참고. 큰 흐름:

- [x] Phase 0 — 레포 스캐폴드
- [ ] Phase 1 — MVP: 3개 서비스 골격 + Compose 한 줄 실행 + 기본 대시보드
- [ ] Phase 2 — OTel 계측 + Loki/Tempo + 의미 있는 알람 + k6
- [ ] Phase 3 — 자체 운영 모듈 적용
- [ ] Phase 4 — 카오스 + 케이스 스터디 누적 (지속)

---

## License

MIT
