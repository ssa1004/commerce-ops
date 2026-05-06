# Runbooks

알람별 대응 절차. 각 알람마다 별도 파일로 추가합니다.

## 작성 규칙

- 파일명 = 알람 이름 (예: `order-p99-latency-high.md`)
- 모든 runbook은 아래 섹션 포함:

```markdown
# <Alert Name>

## When this fires
어떤 조건에서 발화하는지 (PromQL 등)

## Impact
사용자 영향, 비즈니스 영향

## Diagnosis
1. 첫 5분 — 어디부터 볼지 (Grafana 링크, Trace 쿼리 등)
2. 가설 트리

## Mitigation
빠르게 진정시키는 액션 (롤백, 트래픽 차단, 캐시 무효화 등)

## Post-mortem
사후 분석 가이드. case-studies/에 정식 회고로 옮길지 판단.
```

## 목록

> Phase 2에서 채워집니다.

- [ ] order-p99-latency-high
- [ ] error-rate-spike
- [ ] hikari-pool-saturation
- [ ] kafka-consumer-lag
- [ ] gc-pause-too-long
