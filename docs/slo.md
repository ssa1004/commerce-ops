# SLO Definition

SLO (Service Level Objective — "이 정도면 정상이라고 합의한 기준선") 는 "이 서비스가 충분히 신뢰할 만한가" 를 정량화하는 합의입니다.

> Phase 2 에서 Prometheus 알람으로 연동.

---

## Service-Level Indicators (SLI — 실제로 측정하는 신호 자체. SLO 는 SLI 위에 세우는 목표선)

| Service | SLI | 측정 |
|---|---|---|
| order-service | `POST /orders` 성공률 | 5xx 제외한 응답 / 전체 요청 |
| order-service | `POST /orders` p99 응답 시간 (가장 느린 1% 의 컷오프) | http_server_requests p99 |
| payment-service | 결제 처리 성공률 | 결제 결과 이벤트 중 `SUCCESS` 비율 |
| inventory-service | 재고 차감 처리 시간 p95 (가장 느린 5% 컷오프) | 메서드 단위 측정 |

## Service-Level Objectives (SLO)

| Service | SLO | 윈도우 |
|---|---|---|
| order-service availability (가용성, 정상 응답 비율) | **99.5%** | 30일 이동 윈도우 |
| order-service p99 응답 시간 | **< 500ms** | 30일 이동 윈도우 |
| payment-service 성공률 | **99.0%** (외부 PG 의존성 고려) | 30일 이동 윈도우 |
| inventory-service 재고 잡기 p95 | **< 200ms** | 30일 이동 윈도우 |

## Error Budget (오류 예산 — SLO 보다 떨어져도 되는 허용량)

- 99.5% SLO → 30일 중 약 **3.6시간** 다운타임 허용 (이 범위 안에서는 새 기능 배포 등을 자유롭게)
- 알람 정책: error budget burn rate (예산이 소진되는 속도) 기준. Multi-window, multi-burn-rate (짧은 윈도우는 빠른 소진을, 긴 윈도우는 느린 소진을 동시에 감지)

## Burn Rate Alerts (예시)

- **Fast burn (예산이 빠르게 소진)**: 1시간 동안 예산의 2% 소진 → P1 알람
- **Slow burn (꾸준히 새는 중)**: 6시간 동안 5% 소진 → P2 알람

→ 구체적 PromQL (Prometheus 의 쿼리 언어) 은 `infra/prometheus/alerts.yml` 에 정의 (Phase 2)
