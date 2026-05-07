# inventory-lock-timeout-high

## When this fires
inventory-service 분산락 timeout 비율이 5분 평균 1%를 초과.

```promql
sum(rate(inventory_lock_acquire_seconds_count{outcome="timeout"}[5m]))
/
clamp_min(sum(rate(inventory_lock_acquire_seconds_count[5m])), 0.001)
> 0.01
```

## Impact
- 503 LOCK_TIMEOUT 응답 → order-service에서 INVENTORY_INFRA outcome으로 처리되어 주문 실패
- contention이 hot product 한두 개에 몰린 경우가 대부분 (이벤트성 트래픽)

## Diagnosis

### 첫 5분

1. **JVM + HTTP 대시보드** → `inventory_lock_acquire_seconds{outcome=...}` 추세 (acquired/timeout/interrupted 비율)
2. **Tempo** → inventory-service trace 중 lock acquire span이 wait timeout 끝까지 간 trace를 검색 → 어떤 productId인지 attribute에서 확인 (Phase 3 모듈 적용 후 더 풍부)
3. **Loki**:
   ```
   {service_name="inventory-service"} |= "Lock acquisition timed out"
   ```
   timeout 발생 productId가 한두 개에 몰리는지 분포 확인

### 가설 트리

- **Hot product 단일?** → 일시적 이벤트성 트래픽. lock wait time을 길게 (대신 클라이언트 timeout도 같이 봐야 함)
- **lock lease가 너무 짧음** → 작업이 lease 안에 못 끝나면서 자발적 unlock 못한 채 다음 시도가 wait 끝남. lease를 작업 평균 시간보다 충분히 크게 (현재 10s).
- **Redis 자체가 느림?** → Redis 응답 시간 메트릭 확인. 네트워크/포화 가능.
- **불필요한 락 점유 시간** → reserve 트랜잭션 안에서 외부 호출이 들어왔는지 등. 코드 점검.

## Mitigation

- 단기: `INVENTORY_LOCK_WAIT_MS`를 상향 (예: 2000 → 5000ms). 단, order-service의 `INVENTORY_READ_TIMEOUT_MS`(3000ms)보다 길어지면 의미 없음 → 동시에 조정.
- contention이 한 productId에 강하게 몰릴 때: 그 productId만 별도 카운터(예: Redis `INCRBY`로 lock-free decrement) 처리하는 fast path 검토 (후속 작업).
- Redis 인스턴스 자원 점검 (CPU/메모리/네트워크)

## Post-mortem

`case-studies/`에:
- timeout 비율 추세 그래프
- 대상 productId top N
- lock wait/lease 변경 시 비교 결과
- "락 wait > 클라이언트 timeout이면 무용" 같은 일반화 가능한 교훈 기록
