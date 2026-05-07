# order-p99-latency-high

## When this fires
order-service `http_server_requests_seconds` p99이 5분간 500ms를 초과.

```promql
histogram_quantile(
  0.99,
  sum by (le, uri) (rate(http_server_requests_seconds_bucket{application="order-service"}[5m]))
) > 0.5
```

## Impact
- 사용자 결제 흐름 지연 → 이탈 위험
- order p99 ↑ 보통 의존 서비스(payment/inventory) 지연이 누적된 결과

## Diagnosis

### 첫 5분 — 어디부터 볼지

1. **JVM + HTTP 대시보드** → "HTTP p95 latency by URI" 패널에서 어느 URI가 튀는지 확인
   - `/orders` 자체가 느린지, `/orders/{id}`(읽기)가 느린지
2. **Tempo** → 같은 시간대 trace 검색 (느린 trace 위에 떠 있음). span tree로 어디 시간이 쌓이는지:
   - `inventory.reserve` 가 느림 → lock contention 또는 DB 슬로우
   - `payment.charge` 가 느림 → mock-pg latency 또는 외부 PG 의존
   - DB span (`SELECT/UPDATE`) 자체가 느림 → 슬로우 쿼리

### 가설 트리

- **payment 쪽 트리거?** → `/mock-pg/config` 응답으로 latency 설정 확인. `MOCK_PG_LATENCY_MEAN_MS` 비정상 큰지.
- **inventory lock contention?** → `inventory_lock_acquire_seconds{outcome="timeout"}` 비율 동시 상승 확인 (별도 알람도 같이 발화 중일 가능성).
- **DB 풀 고갈?** → `hikari_pool_saturation` 알람 동시 발화 여부.
- **전반적인 GC pause?** → `jvm_gc_pause_too_long` 동시 발화 여부.
- **트래픽 자체가 평소보다 많음?** → request rate 패널 비교.

## Mitigation

상황별:
- 외부 PG 의존성 지연: payment-service의 read timeout을 임시 상향, 또는 PG 장애로 인지하면 재시도 정책 적용
- inventory lock contention: hot product에 대한 lock lease 짧게(불필요한 점유 줄이기), wait를 길게 → 큰 효과 없으면 lock-free 경로 (예: 분산 카운터) 검토 후속
- DB 풀 고갈: 가까운 시간 내 인스턴스 증설 또는 풀 크기 임시 상향 + 외부 호출이 트랜잭션 안에 있는지 점검 (이건 코드 fix)
- GC pause: 큰 객체 할당 핫스팟 분석 (JFR), -Xmx 임시 상향

## Post-mortem

30분 이상 영향이 있었다면 `case-studies/`에 정식 회고:
- trace 캡처 + flamegraph
- 가설→검증 흐름
- 영구 fix와 임시 mitigation 분리
