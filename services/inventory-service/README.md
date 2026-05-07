# inventory-service

재고 차감/복구. **Redisson 분산락**으로 동시성 제어 + **(orderId, productId) 단위 멱등성**.

## API

| Method | Path | 상태 |
|---|---|---|
| POST | `/inventories/reserve` | ✅ Step 3 |
| POST | `/inventories/release` | ✅ Step 3 |
| GET | `/inventories/{productId}` | ✅ Step 3 |

## 도메인 모델

- `Inventory` — productId, availableQuantity, JPA `@Version` (낙관적 락)
- `InventoryReservation` — productId, orderId, quantity, status, timestamps
- `ReservationStatus` — RESERVED / RELEASED
- 유니크 제약: `(orderId, productId)` → 멱등성의 핵심

## 멱등성 설계

- **Reserve**: 같은 `(orderId, productId)`로 다시 호출되면 같은 reservation을 그대로 반환 (`idempotent=true`). 재고는 한 번만 차감.
- **Release**: 동일 키가 RELEASED면 no-op. 재고는 한 번만 복구.

이게 SAGA·재시도·Kafka 중복 메시지 어떤 상황에서도 재고가 음수로 가거나 두 배로 복구되는 것을 막는다.

## 동시성 제어 (이중 안전망)

1. **Redisson 분산락** (`mini-shop.inventory.lock.*`) — 멀티 인스턴스에서도 동일 productId에 대한 동시 진입 차단. 락 → 트랜잭션 순서 (커넥션 풀 점유 최소화).
2. **JPA `@Version`** (낙관적 락) — 락 timeout으로 동시 진입이 발생해도 DB 레벨에서 한 번 더 막힘.

| 환경변수 | 기본값 | 의미 |
|---|---|---|
| `INVENTORY_LOCK_KEY_PREFIX` | `inv` | Redis 키 prefix |
| `INVENTORY_LOCK_WAIT_MS` | 2000 | 락 획득 대기 시간 |
| `INVENTORY_LOCK_LEASE_MS` | 10000 | 락 보유 시간 (작업 시간보다 충분히 길게) |

락 획득 결과는 메트릭 `inventory_lock_acquire_seconds{outcome=acquired|timeout|interrupted}`으로 노출.

## HTTP 응답

| 상황 | 상태 코드 |
|---|---|
| 새 reservation | 201 Created |
| 멱등 reserve | 200 OK (`idempotent=true`) |
| 재고 부족 | 409 Conflict (`OUT_OF_STOCK` + 현재 가용량 포함) |
| 락 획득 실패 (대기 timeout) | 503 Service Unavailable (`LOCK_TIMEOUT`) → 재시도 권장 |
| 상품/예약 없음 | 404 Not Found |

## 실행

```bash
docker compose -f ../../infra/docker-compose.yml up -d postgres redis
./gradlew bootRun
```

기본값: `localhost:5432/inventorydb`, Redis `localhost:6379`, 포트 8083.

```bash
curl -X POST localhost:8083/inventories/reserve \
  -H "Content-Type: application/json" \
  -d '{"productId":1001, "orderId":42, "quantity":2}'
```

V2 시드: `productId=1001` 100개, `1002` 50개, `1003` 25개.

## 메트릭

`/actuator/prometheus`:
- JVM/HTTP 표준
- `inventory_lock_acquire_seconds{outcome=...}` — 락 대기/획득 시간 + 결과별 분포

## 테스트

```bash
./gradlew test
```

- `InventoryServiceApplicationTests` — Postgres + Redis Testcontainers
  - 재예약 멱등성 (재고 한 번만 차감)
  - 재해제 멱등성 (재고 한 번만 복구)
  - 재고 부족 시 409
  - lock 메트릭 노출

**Docker 데몬 필요**.

## Step 3 체크리스트

- [x] Spring Boot 프로젝트 초기화 (Redis 의존성 포함)
- [x] Flyway V1 (스키마) + V2 (시드)
- [x] `Inventory` + `InventoryReservation` 도메인 + 멱등 키
- [x] `DistributedLockService` (Redisson 래퍼 + 메트릭)
- [x] `InventoryService` (락 → 트랜잭션 순서, 멱등 reserve/release)
- [x] `GlobalExceptionHandler` (의미 있는 HTTP 코드 매핑)
- [x] Testcontainers 통합 테스트 (Postgres + Redis)
- [x] Prometheus scrape 활성화 (8083)
