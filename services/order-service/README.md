# order-service

주문 도메인의 entry point. 결제·재고 흐름을 시작/조정.

## API

| Method | Path | 상태 |
|---|---|---|
| POST | `/orders` | ✅ Step 1 |
| GET | `/orders/{id}` | ✅ Step 1 |
| GET | `/orders` (paging) | Step 4 (wiring) |

## 도메인 모델

- `Order` — id, userId, status, items[], totalAmount, createdAt, updatedAt
- `OrderItem` — productId, quantity, price
- `OrderStatus` — PENDING / PAID / FAILED / CANCELLED

`Order.create(...)`가 unit-of-work entry point. `addItem`에서 양방향 연관관계를 채우고 totalAmount 계산.

## 실행

```bash
# 사전: 인프라 컴포즈 기동 (postgres 포함)
docker compose -f ../../infra/docker-compose.yml up -d postgres

# 실행
./gradlew bootRun
```

기본값: `localhost:5432/orderdb` (user/pwd: `mini`/`mini`), 서버 포트 8081.
환경변수: `DB_HOST`, `DB_PORT`, `DB_USER`, `DB_PASSWORD`, `SERVER_PORT`.

## 메트릭

`/actuator/prometheus` — Micrometer JVM/HTTP/JDBC/Hibernate 메트릭. Prometheus가 `host.docker.internal:8081`에서 스크랩.
대시보드: Grafana → "JVM + HTTP".

## 테스트

```bash
./gradlew test
```

`OrderServiceApplicationTests` — Testcontainers Postgres로 부팅, `POST /orders` → `GET /orders/{id}` 왕복 + Prometheus endpoint 메트릭 노출 확인. **Docker 데몬 필요**.

## 외부 의존성

- PostgreSQL (`orderdb`)
- (Step 4) `payment-service`, `inventory-service` 동기 호출
- (Phase 2) Kafka producer: `order.created`, consumer: `payment.result`, `inventory.result`

## Step 1 체크리스트

- [x] Spring Boot 프로젝트 초기화 (Initializr)
- [x] Flyway 마이그레이션 (`orders`, `order_items`)
- [x] 도메인 엔티티 + Repository
- [x] `POST /orders` 핵심 로직
- [x] Micrometer Prometheus 노출
- [x] Testcontainers 통합 테스트
- [x] Prometheus scrape 활성화
- [x] Grafana JVM+HTTP 대시보드
