# order-service

주문 도메인의 진입점. **결제·재고 동기 오케스트레이션** (한 서비스가 다른 서비스를 직접 호출하며 흐름을 지휘) + 실패 시 보상 (SAGA 의 동기 버전 — 비동기 이벤트 대신 REST 호출로 묶음).

## API

| Method | Path | 상태 |
|---|---|---|
| POST | `/orders` | ✅ Step 4 (오케스트레이션) |
| GET | `/orders/{id}` | ✅ (fetch join — 연관 엔티티까지 한 쿼리로 가져와서 N+1 회피) |
| GET | `/orders` (paging) | ✅ **의도적 N+1 데모** (한 번의 마스터 조회 + N번의 자식 조회 패턴) — `slow-query-detector` 가 자동 감지 |

## 흐름

```
POST /orders
  │
  ├─ Order(PENDING) 저장
  │
  ├─ for each item:
  │     inventory-service.reserve(productId, orderId, qty)   // 멱등 키
  │     ├─ 성공 → 누적
  │     └─ 실패(OutOfStock) → 누적된 것들 release + Order(FAILED) → 409
  │
  ├─ payment-service.charge(orderId, userId, total)
  │     ├─ SUCCESS → Order(PAID)                              → 201
  │     ├─ FAILED  → 모든 reserve release + Order(FAILED)    → 402
  │     └─ infra error → 모든 reserve release + Order(FAILED) → 502
  │
  └─ infra error during reserve → release + Order(FAILED)    → 503
```

응답 헤더 `X-Order-Outcome`에 `OUT_OF_STOCK / PAYMENT_DECLINED / INVENTORY_INFRA / PAYMENT_INFRA` 분류.

## HTTP 응답 매핑

| 결과 | 상태 코드 |
|---|---|
| 결제 성공 | **201 Created** |
| 결제 거절 | **402 Payment Required** |
| 재고 부족 | **409 Conflict** |
| 결제 서비스 장애 | **502 Bad Gateway** |
| 재고 서비스 장애 | **503 Service Unavailable** |

장애 응답에도 응답 본문에는 현재 Order 상태(=FAILED)가 들어가서 클라이언트가 추적 가능.

## 도메인 모델

- `Order` — id, userId, status, items[], totalAmount, createdAt, updatedAt
- `OrderItem` — productId, quantity, price
- `OrderStatus` — PENDING / PAID / FAILED / CANCELLED

`Order.create(...)` 가 객체 생성의 단일 진입점 (unit-of-work entry point — 한 트랜잭션 단위 작업의 시작). `addItem` 에서 양방향 연관관계 (Order ↔ OrderItem 양쪽 참조 채우기) 와 totalAmount 계산을 처리.

## 실행

```bash
# 인프라
docker compose -f ../../infra/docker-compose.yml up -d postgres redis

# 의존 서비스 (다른 셸에서)
cd ../payment-service   && ./gradlew bootRun
cd ../inventory-service && ./gradlew bootRun

# order-service
./gradlew bootRun
```

기본값: `localhost:5432/orderdb`, 포트 8081.

| 환경변수 | 기본값 | 의미 |
|---|---|---|
| `PAYMENT_URL` | http://localhost:8082 | payment-service base URL |
| `INVENTORY_URL` | http://localhost:8083 | inventory-service base URL |
| `PAYMENT_CONNECT_TIMEOUT_MS` / `READ_TIMEOUT_MS` | 1000 / 5000 | 결제 호출 타임아웃 |
| `INVENTORY_CONNECT_TIMEOUT_MS` / `READ_TIMEOUT_MS` | 1000 / 3000 | 재고 호출 타임아웃 |

## 메트릭

`/actuator/prometheus`:
- 표준 JVM/HTTP
- `order_orchestration_seconds{outcome=paid|out_of_stock|payment_declined|inventory_infra|payment_infra}` — 결과별 처리 시간 + 분포
- `slow_query_total`, `n_plus_one_total`, `query_execution_seconds{outcome=ok|slow}` — `modules/slow-query-detector` v0.1 이 노출 (composite build — 여러 Gradle 프로젝트를 하나처럼 묶어 빌드 — 로 의존성 추가됨)
- `outbox.publish{topic, outcome}` — Outbox 폴러 (DB 에 쌓인 미발행 이벤트를 주기적으로 가져와 Kafka 에 발행하는 백그라운드 작업)
- `inbox.consume{topic, outcome}`, `reconciliation.inconsistency{kind}` — 인박스 (다른 서비스 이벤트를 멱등 저장하는 거울 테이블) / 부정합 모니터링

## 테스트

```bash
./gradlew test
```

`OrderServiceApplicationTests` — Postgres Testcontainers (테스트 시작 시 진짜 DB 컨테이너를 띄워서 검증) + `@MockitoBean InventoryClient/PaymentClient` (Spring 빈을 Mockito 가짜로 교체). 검증:
- 정상 흐름: 201, 모든 item 재고 잡기, 결제 1회, 해제 0회
- OUT_OF_STOCK: 409, **이미 잡은 항목만 보상 해제**, 결제 호출 안 함
- PAYMENT_DECLINED: 402, 잡은 재고 모두 보상 해제
- Prometheus 가 orchestration 메트릭을 노출

**Docker 데몬 필요**.

## 주요 설계 포인트

- **락 → 트랜잭션 순서**: 재고 잡기/해제는 inventory-service 내부에서 락을 먼저 잡고, 그 안에서 짧은 트랜잭션을 연다. order-service 트랜잭션은 짧게 (저장만) 유지하고 외부 호출은 트랜잭션 밖에서 — 외부 호출 동안 DB 커넥션을 쥐고 있지 않음.
- **멱등성 위임**: 같은 orderId 로 재시도해도 inventory-service 의 `(orderId, productId)` 멱등성 덕분에 재고가 두 번 차감되지 않음.
- **timeout 짧게 잡기**: payment-service 의 외부 PG read timeout (5s) 보다 order-service 의 payment 호출 read timeout (5s) 이 같거나 길게 잡히지 않게 — 짧게 끊어서 빠르게 보상하는 편이 운영에 유리. (Phase 4 케이스 스터디 소재)
- **5xx 와 비즈니스 실패 분리**: 4xx (409/402) 는 의도된 비즈니스 결과 (재고 없음·결제 거절은 시스템 장애 아님). k6 임계값도 `5xx` 만 SLO 위반으로 카운트.
