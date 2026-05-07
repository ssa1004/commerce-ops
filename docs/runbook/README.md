# Runbooks

알람별 대응 절차. 각 알람마다 별도 파일로 추가합니다.

## 작성 규칙

- 파일명 = 알람 이름 (예: `order-p99-latency-high.md`)
- alertmanager의 `runbook_url`이 GitHub raw URL을 가리키므로 파일 이름과 알람 이름은 정확히 일치시킨다
- 모든 runbook은 다음 섹션 포함:

```markdown
# <Alert Name>

## When this fires
어떤 조건에서 발화하는지 (PromQL 등)

## Impact
사용자 영향, 비즈니스 영향

## Diagnosis
1. 첫 5분 — 어디부터 볼지 (Grafana 패널, Tempo/Loki 쿼리)
2. 가설 트리

## Mitigation
빠르게 진정시키는 액션 (롤백, 트래픽 차단, 캐시 무효화 등)

## Post-mortem
사후 분석 가이드. case-studies/에 정식 회고로 옮길지 판단.
```

## 목록

Phase 2 Step 2 — 5개:
- [order-p99-latency-high](order-p99-latency-high.md) — order-service p99 > 500ms
- [order-error-rate-spike](order-error-rate-spike.md) — order-service 5xx 비율 > 1%
- [hikari-pool-saturation](hikari-pool-saturation.md) — Hikari active/max > 90%
- [gc-pause-too-long](gc-pause-too-long.md) — 평균 GC pause > 200ms
- [inventory-lock-timeout-high](inventory-lock-timeout-high.md) — 분산락 timeout 비율 > 1%

Phase 3 Step 2 — slow-query-detector 적용 후 추가:
- [n-plus-one-detected](n-plus-one-detected.md) — N+1 패턴 감지 (`n_plus_one_total > 0`)

## 다음에 추가할 후보

- payment p99 latency high (mock-pg 의존)
- kafka consumer lag (Phase 2 Step 3 이후)
- redis connection refused
- DB connection refused
