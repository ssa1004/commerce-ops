# mini-shop-observability

> 작은 이커머스 마이크로서비스를 직접 운영하면서 **관측성 인프라**, **Spring Boot 운영 라이브러리**, **장애 분석 케이스 스터디**를 함께 쌓는 포트폴리오 프로젝트.

이 레포의 목적은 두 가지입니다.

1. **포트폴리오** — 서비스 코드뿐 아니라 운영 설계, 관측성, 알람, 장애 대응 문서까지 한 저장소에서 보여준다.
2. **개인 학습** — 백엔드와 DevOps 역량을 "실제 운영에서 무엇을 보고 어떻게 판단할 것인가"의 형태로 축적한다.

## Current Status

현재 저장소는 **Phase 0 스캐폴드 + 관측성 인프라 설정** 단계입니다. Spring Boot 서비스 소스는 아직 생성 전이며, `services/`와 `modules/` 아래 README는 구현 계획과 설계 기준을 설명합니다.

| 영역 | 현재 상태 |
|---|---|
| Docker Compose infra | PostgreSQL, Redis, Kafka, Prometheus, Loki, Tempo, Grafana, Alertmanager 구성 |
| Grafana dashboard | Prometheus 자체 상태를 보는 infra overview dashboard 프로비저닝 |
| Service source | Phase 1에서 생성 예정 |
| Custom modules | Phase 3에서 구현 예정인 운영 라이브러리 설계 문서 |
| Load / chaos | 시나리오 템플릿과 실행 기준 정리 |
| Case studies | 운영 회고 템플릿 준비 |

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

현재 단계에서 바로 실행 가능한 범위는 **공통 인프라 스택**입니다.

```bash
docker compose -f infra/docker-compose.yml up -d
docker compose -f infra/docker-compose.yml ps
```

설정 파일만 빠르게 검증하려면:

```bash
docker compose -f infra/docker-compose.yml config
```

확인할 곳:

| URL | 용도 |
|---|---|
| http://localhost:3000 | Grafana (admin / admin) |
| http://localhost:9090 | Prometheus |
| http://localhost:3200 | Tempo |
| http://localhost:9093 | Alertmanager |

서비스 실행과 `./gradlew bootRun`은 Phase 1에서 Spring Boot 프로젝트가 생성된 뒤 추가됩니다. 인프라 실행 세부 내용은 [infra/README.md](infra/README.md)를 참고하세요.

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
- [ ] Phase 1 — MVP: 3개 서비스 골격 + Compose 한 줄 실행 + 기본 대시보드
- [ ] Phase 2 — OTel 계측 + Loki/Tempo + 의미 있는 알람 + k6
- [ ] Phase 3 — 자체 운영 모듈 적용
- [ ] Phase 4 — 카오스 + 케이스 스터디 누적 (지속)

---

## License

MIT
