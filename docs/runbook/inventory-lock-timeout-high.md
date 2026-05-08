# inventory-lock-timeout-high

## When this fires
inventory-service 분산락 timeout (락 획득 대기 중 시간 초과) 비율이 5분 평균 1% 를 초과.

```promql
sum(rate(inventory_lock_acquire_seconds_count{outcome="timeout"}[5m]))
/
clamp_min(sum(rate(inventory_lock_acquire_seconds_count[5m])), 0.001)
> 0.01
```

## Impact
- inventory-service 가 503 LOCK_TIMEOUT 으로 응답 → order-service 가 그 응답을 받아 X-Order-Outcome=INVENTORY_INFRA 로 분류 → 주문 실패.
- 경합 (contention — 같은 자원을 두고 여러 요청이 줄 서는 상태) 이 인기 상품 한두 개에 몰린 경우가 대부분 (이벤트성 트래픽 — 깜짝 세일/타임딜).

## Diagnosis

### 첫 5분

1. **JVM + HTTP 대시보드** → `inventory_lock_acquire_seconds{outcome=...}` 추세 (acquired = 획득 / timeout = 시간초과 / interrupted = 인터럽트로 끊김 비율)
2. **Tempo** → inventory-service trace 중 락 획득 span 이 대기 시간 끝까지 간 trace 를 검색 → 어떤 productId 인지 attribute (span 에 붙은 키밸류 메타데이터) 에서 확인 (Phase 3 모듈 적용 후 더 풍부)
3. **Loki**:
   ```
   {service_name="inventory-service"} |= "Lock acquisition timed out"
   ```
   timeout 발생 productId 가 한두 개에 몰리는지 분포 확인

### 가설 트리

- **인기 상품 한 개에 몰림?** → 일시적 이벤트성 트래픽. 락 대기 시간 (wait) 을 길게 (대신 클라이언트 timeout 도 같이 봐야 함 — 더 짧으면 의미 없음)
- **락 점유 시간 (lease) 이 너무 짧음** → 작업이 lease 안에 못 끝나면 자동 해제되어, 그동안 대기하던 다음 시도와 충돌. lease 를 작업 평균 시간보다 충분히 크게 (현재 10s).
- **Redis 자체가 느림?** → Redis 응답 시간 메트릭 확인. 네트워크/CPU 포화 가능.
- **불필요한 락 점유 시간** → reserve 트랜잭션 안에서 외부 호출이 들어왔는지 등. 코드 점검.

## Mitigation

- 단기: `INVENTORY_LOCK_WAIT_MS` 를 상향 (예: 2000 → 5000ms). 단, order-service 의 `INVENTORY_READ_TIMEOUT_MS` (3000ms) 보다 길어지면 의미 없음 (호출자가 먼저 끊으니까) → 동시에 조정.
- 경합이 한 productId 에 강하게 몰릴 때: 그 productId 만 별도 카운터 (예: Redis `INCRBY` 로 락 없이 감산) 로 처리하는 fast path 검토 (후속 작업).
- Redis 인스턴스 자원 점검 (CPU / 메모리 / 네트워크)

## Post-mortem

`case-studies/` 에:
- timeout 비율 추세 그래프
- 대상 productId top N
- 락 wait / lease 변경 시 비교 결과
- "락 wait > 클라이언트 timeout 이면 무용" 같은 일반화 가능한 교훈 기록
