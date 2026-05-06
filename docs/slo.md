# SLO Definition

SLO는 "이 서비스가 충분히 신뢰할 만한가"를 정량화하는 합의입니다.

> Phase 2에서 Prometheus 알람으로 연동.

---

## Service-Level Indicators (SLI)

| Service | SLI | 측정 |
|---|---|---|
| order-service | `POST /orders` 성공률 | 5xx 제외한 응답 / 전체 요청 |
| order-service | `POST /orders` p99 latency | http_server_requests p99 |
| payment-service | 결제 처리 성공률 | 결제 결과 이벤트 중 `SUCCESS` 비율 |
| inventory-service | 재고 차감 처리 시간 p95 | 메서드 단위 측정 |

## Service-Level Objectives (SLO)

| Service | SLO | 윈도우 |
|---|---|---|
| order-service availability | **99.5%** | 30일 롤링 |
| order-service p99 latency | **< 500ms** | 30일 롤링 |
| payment-service success rate | **99.0%** (외부 PG 의존성 고려) | 30일 롤링 |
| inventory-service p95 reservation | **< 200ms** | 30일 롤링 |

## Error Budget

- 99.5% SLO → 30일 중 약 **3.6시간** 다운타임 허용
- 알람 정책: error budget burn rate 기준 (Multi-window, multi-burn-rate)

## Burn Rate Alerts (예시)

- **Fast burn**: 1시간 동안 budget의 2% 소진 → P1 알람
- **Slow burn**: 6시간 동안 5% 소진 → P2 알람

→ 구체적 PromQL은 `infra/prometheus/alerts.yml`에 정의 (Phase 2)
