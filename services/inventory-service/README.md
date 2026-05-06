# inventory-service

재고 차감/복구. Redis 캐시 + 분산락으로 동시성 제어.

## API (Phase 1 목표)

| Method | Path | 설명 |
|---|---|---|
| POST | `/inventories/reserve` | 재고 차감 |
| POST | `/inventories/release` | 재고 복구 (보상) |
| GET | `/inventories/{productId}` | 재고 조회 |

## 핵심 로직

```
reserve(productId, qty):
  lock = redisson.getLock("inv:" + productId)
  lock.tryLock(...)
  try:
    current = cacheGetOrLoad(productId)
    if current < qty: throw OutOfStock
    update DB (atomic decrement)
    cacheSet(productId, current - qty)
  finally: lock.unlock()
```

## 의도적인 결함 (데모용)

- Phase 4 케이스 스터디에서 활용:
  - 락 timeout 짧게 → contention 발생
  - 캐시 TTL 짧게 → DB 부하 증가
  - 트랜잭션 격리 수준 변경 → 팬텀 리드 관찰

## TODO (Phase 1)

- [ ] Spring Boot + Redis + Redisson
- [ ] `Inventory` 엔티티 + Flyway
- [ ] 분산락 적용한 차감 로직
- [ ] 메트릭: 차감 성공률, lock wait time, cache hit ratio
