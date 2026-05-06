# order-service

주문 도메인의 entry point. 결제·재고 흐름을 시작/조정.

## API (Phase 1 목표)

| Method | Path | 설명 |
|---|---|---|
| POST | `/orders` | 주문 생성 |
| GET | `/orders/{id}` | 주문 조회 |
| GET | `/orders` | 주문 목록 (paging) |

## 도메인 모델 (초안)

- `Order` — id, userId, status, items[], totalAmount, createdAt
- `OrderItem` — productId, quantity, price
- `OrderStatus` — PENDING / PAID / FAILED / CANCELLED

## 외부 의존성

- PostgreSQL (`orderdb`)
- (Phase 1) `payment-service` 동기 호출
- (Phase 2) Kafka producer: `order.created`, consumer: `payment.result`, `inventory.result`

## TODO (Phase 1)

- [ ] Spring Boot 프로젝트 초기화
- [ ] Flyway 마이그레이션 (`orders`, `order_items`)
- [ ] 도메인 엔티티 + Repository
- [ ] `POST /orders` 핵심 로직
- [ ] Micrometer 메트릭 노출 (`/actuator/prometheus`)
- [ ] Testcontainers 통합 테스트
