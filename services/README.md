# Services

세 개의 Spring Boot 마이크로서비스. 각 서비스는 독립 Gradle 프로젝트.

| Service | Port | DB | 책임 |
|---|---|---|---|
| `order-service` | 8081 | `orderdb` | 주문 생성/조회, 결제·재고 오케스트레이션 |
| `payment-service` | 8082 | `paymentdb` | 결제 처리, 외부 PG 호출 (mock) |
| `inventory-service` | 8083 | `inventorydb` | 재고 차감/복구, Redis 캐시 |

## 공통 스택

- Java 21 / Spring Boot 3.x / Gradle (Kotlin DSL)
- Spring Web, Data JPA, Validation
- Flyway (migration)
- Spring Kafka (Phase 2부터)
- Micrometer + OTel
- Testcontainers (PostgreSQL, Kafka, Redis)

## Phase 1 생성 가이드

각 서비스 디렉토리에서 (Phase 1 작업):

```bash
# Spring Initializr로 시작 (예시)
curl https://start.spring.io/starter.zip \
  -d type=gradle-project-kotlin \
  -d language=java \
  -d javaVersion=21 \
  -d bootVersion=3.3.0 \
  -d groupId=io.minishop \
  -d artifactId=order-service \
  -d dependencies=web,data-jpa,actuator,validation,flyway,postgresql \
  -o starter.zip
unzip starter.zip && rm starter.zip
```

## 서비스 간 통신

- **Phase 1**: 동기 REST 호출 (`order` → `payment` → `inventory`)
- **Phase 2**: Kafka 이벤트로 전환 (`OrderCreated` / `PaymentSucceeded` / `InventoryReserved`)
