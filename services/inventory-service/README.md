# inventory-service

재고 차감/복구. **Redisson 분산락** (여러 인스턴스가 같은 자원에 동시 접근 못 하게 Redis 로 잠그는 도구) 으로 동시성 제어 + **(orderId, productId) 단위 멱등성** (같은 키 요청이 두 번 와도 한 번 처리한 것과 같은 결과).

## API

| Method | Path | 상태 |
|---|---|---|
| POST | `/inventories/reserve` | ✅ Step 3 |
| POST | `/inventories/release` | ✅ Step 3 |
| GET | `/inventories/{productId}` | ✅ Step 3 |

## 도메인 모델

- `Inventory` — productId, availableQuantity, JPA `@Version` (낙관적 락 — 같은 행을 두 트랜잭션이 동시에 바꾸면 뒤늦은 쪽이 실패)
- `InventoryReservation` — productId, orderId, quantity, status, timestamps
- `ReservationStatus` — RESERVED / RELEASED
- 유니크 제약: `(orderId, productId)` → 멱등성의 핵심 (같은 키로 두 행이 만들어질 수 없음)

## 멱등성 설계

- **재고 잡기 (Reserve)**: 같은 `(orderId, productId)` 로 다시 호출되면 같은 reservation 을 그대로 반환 (`idempotent=true`). 재고는 한 번만 차감.
- **해제 (Release)**: 같은 키가 이미 RELEASED 면 아무것도 안 함. 재고는 한 번만 복구.

이게 SAGA·재시도·Kafka 중복 메시지 어떤 상황에서도 재고가 음수로 가거나 두 배로 복구되는 것을 막는다.

## 동시성 제어 (2개 레이어)

1. **Redisson 분산락** (`mini-shop.inventory.lock.*`) — 여러 인스턴스에서도 동일 productId 에 대한 동시 진입 차단. 락 → 트랜잭션 순서 (락 잡기 전에 트랜잭션을 안 열어 커넥션 풀 점유 최소화).
2. **JPA `@Version`** (낙관적 락) — 락 timeout 으로 동시 진입이 발생해도 DB 레벨에서 한 번 더 막힘.

| 환경변수 | 기본값 | 의미 |
|---|---|---|
| `INVENTORY_LOCK_KEY_PREFIX` | `inv` | Redis 키의 앞 글자 (서비스 구분) |
| `INVENTORY_LOCK_WAIT_MS` | 2000 | 락 획득 대기 시간 |
| `INVENTORY_LOCK_LEASE_MS` | 10000 | 락 보유 시간 (작업 시간보다 충분히 길게 — 도중 만료되면 다른 요청이 끼어들 수 있음) |

락 획득 결과는 메트릭 `inventory_lock_acquire_seconds{outcome=acquired|timeout|interrupted}` 으로 노출 (acquired = 획득, timeout = 대기 끝남, interrupted = 인터럽트로 끊김).

## HTTP 응답

| 상황 | 상태 코드 |
|---|---|
| 새 reservation | 201 Created |
| 멱등 reserve (이미 처리된 키 재요청) | 200 OK (`idempotent=true`) |
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
- [x] `GlobalExceptionHandler` (도메인 예외 → HTTP 코드 매핑)
- [x] Testcontainers 통합 테스트 (Postgres + Redis)
- [x] Prometheus scrape 활성화 (8083)

## DLQ admin REST API

DLQ 관리 콘솔의 백엔드 8 endpoint. 표준은 ADR-026 (DLQ admin REST API 표준 v2).
notification-hub / billing-platform / bid-ask-marketplace / gpu-job-orchestrator 의 검증된 모양.

inventory 특유:
- `DlqSource` = `RESERVE_FAILED / RELEASE_FAILED / KAFKA_CONSUME / OUTBOX`
- stats 차원 `byProduct` + `bySku` (변종/색상 단위 분리)
- **replay 시 Redisson 분산락 재획득** — `product:{productId}` 키로 정상 reserve/release 와 *같은
  prefix* + *같은 wait/lease*. 락 timeout 이면 응답 `lockAcquired=false` + `reason=LOCK_TIMEOUT` 으로
  콘솔이 즉시 인지. 락 없이 재처리하면 재고가 음수가 되는 동시성 사고 — 본 service 의 replay 가
  가장 위험한 작업이라 lock 결과를 응답에 *반드시* 노출.
- `KAFKA_CONSUME` / `OUTBOX` 의 replay 는 락 대상 아님 (consumer 자체 멱등 / outbox 는 발행 자체).

| Method | Path | scope |
|---|---|---|
| GET | `/api/v1/admin/dlq` | `dlq.read` |
| GET | `/api/v1/admin/dlq/{messageId}` | `dlq.read` |
| POST | `/api/v1/admin/dlq/{messageId}/replay` (`X-Idempotency-Key`) | `dlq.write` |
| POST | `/api/v1/admin/dlq/{messageId}/discard` (`{reason}`) | `dlq.write` |
| POST | `/api/v1/admin/dlq/bulk-replay` (`source` 필수, dry-run 강제) | `dlq.bulk` |
| POST | `/api/v1/admin/dlq/bulk-discard` (`source`+`reason` 필수, dry-run 강제) | `dlq.bulk` |
| GET | `/api/v1/admin/dlq/bulk-jobs/{jobId}` | `dlq.read` |
| GET | `/api/v1/admin/dlq/stats?from=&to=&bucket=PT1H` | `dlq.read` |

권한 / 안전 (ADR-026 공통):
- `X-Admin-Role: DLQ_ADMIN` (또는 `PLATFORM_ADMIN`) 헤더 필요.
- `X-Actor: <user>` 헤더로 audit actor 기록.
- rate limit `admin:dlq:<ip>` scope 별 분당 `read=60 / write=30 / bulk=5`. 초과 시 `429 + Retry-After`.
- `bulk-*` 는 `source` 필수, `confirm=false` 면 항상 dry-run.
- `bulk-discard` 는 hard DELETE 차단 (soft delete + retention).

운영 curl 예시:

```bash
BASE=http://localhost:8083/api/v1/admin/dlq

# 1) 최근 RESERVE_FAILED 조회
curl -s -H "X-Admin-Role: DLQ_ADMIN" -H "X-Actor: alice" \
  "$BASE?source=RESERVE_FAILED&size=20"

# 2) 단건 replay — 응답의 lockAcquired 가 false 면 분산락 timeout 사유 (재시도 권장)
curl -s -X POST \
  -H "X-Admin-Role: DLQ_ADMIN" -H "X-Actor: alice" \
  -H "X-Idempotency-Key: $(uuidgen)" \
  "$BASE/<messageId>/replay"

# 3) bulk-replay DRY-RUN (RESERVE_FAILED 의 LOCK_TIMEOUT 만)
curl -s -X POST \
  -H "X-Admin-Role: DLQ_ADMIN" -H "X-Actor: alice" \
  -H "Content-Type: application/json" \
  -d '{"source":"RESERVE_FAILED","errorType":"LOCK_TIMEOUT","maxMessages":200}' \
  "$BASE/bulk-replay"

# 4) 통계 (byProduct + bySku 차원)
curl -s -H "X-Admin-Role: DLQ_ADMIN" \
  "$BASE/stats?from=2026-05-17T00:00:00Z&to=2026-05-18T00:00:00Z&bucket=PT1H"
```

