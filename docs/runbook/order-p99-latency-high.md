# order-p99-latency-high

## When this fires
order-service `http_server_requests_seconds` p99 (가장 느린 1% 응답의 컷오프) 이 5분간 500ms 를 초과.

```promql
histogram_quantile(
  0.99,
  sum by (le, uri) (rate(http_server_requests_seconds_bucket{application="order-service"}[5m]))
) > 0.5
```

## Impact
- 사용자 결제 흐름 지연 → 이탈 위험
- order p99 상승은 보통 의존 서비스 (payment/inventory) 지연이 누적된 결과

## Diagnosis

### 첫 5분 — 어디부터 볼지

1. **JVM + HTTP 대시보드** → "HTTP p95 latency by URI" 패널에서 어느 URI 가 튀는지 확인
   - `/orders` 자체가 느린지, `/orders/{id}` (읽기) 가 느린지
2. **Tempo** → 같은 시간대 trace 검색 (느린 trace 가 위에 떠 있음). span tree (요청 하나의 호출 흐름) 로 어디서 시간이 쌓이는지:
   - `inventory.reserve` 가 느림 → 락 경합 (여러 요청이 같은 자원을 두고 기다리는 상태) 또는 DB 슬로우 쿼리
   - `payment.charge` 가 느림 → mock-pg 지연 또는 외부 PG 의존
   - DB span (`SELECT/UPDATE`) 자체가 느림 → 슬로우 쿼리

### 가설 트리

- **payment 쪽이 트리거?** → `/mock-pg/config` 응답으로 지연 설정 확인. `MOCK_PG_LATENCY_MEAN_MS` 가 비정상 큰지.
- **inventory 락 경합?** → `inventory_lock_acquire_seconds{outcome="timeout"}` 비율 동시 상승 확인 (별도 알람도 같이 발화 중일 가능성).
- **DB 커넥션 풀 고갈?** → `hikari_pool_saturation` 알람 동시 발화 여부.
- **전반적인 GC pause (가비지 컬렉션이 앱을 잠시 멈추는 시간) ?** → `jvm_gc_pause_too_long` 동시 발화 여부.
- **트래픽 자체가 평소보다 많음?** → request rate 패널 비교.

## Mitigation

상황별:
- 외부 PG 의존성 지연: payment-service 의 read timeout 을 임시 상향, 또는 PG 장애로 인지하면 재시도 정책 적용
- inventory 락 경합: 인기 상품 (hot product) 의 락 점유 시간 (lease) 을 짧게 (불필요한 점유 줄이기), 대기 시간은 길게 → 큰 효과 없으면 락 없이 처리하는 경로 (예: Redis INCRBY 같은 분산 카운터) 검토 후속
- DB 풀 고갈: 가까운 시간 내 인스턴스 증설 또는 풀 크기 임시 상향 + 외부 HTTP 호출이 트랜잭션 안에 들어와 있지 않은지 점검 (이건 코드 fix — 외부 호출이 트랜잭션 안에 있으면 그동안 DB 커넥션을 쥐고 있어서 풀이 빨리 마름)
- GC pause: 큰 객체 할당 핫스팟 분석 (JFR — Java Flight Recorder, JDK 내장 프로파일러), -Xmx (JVM 최대 힙 크기) 임시 상향

## Post-mortem

30분 이상 영향이 있었다면 `case-studies/` 에 정식 회고:
- trace 캡처 + flamegraph (호출 스택을 시간 비중으로 시각화한 그래프)
- 가설 → 검증 흐름
- 영구 fix 와 임시 완화책 (mitigation) 분리
