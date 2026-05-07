# Services

세 개의 Spring Boot 마이크로서비스를 둘 예정입니다. 현재는 서비스별 설계 README만 있고, 실제 Gradle 프로젝트와 소스 코드는 Phase 1에서 생성합니다.

| Service | Port | DB | 책임 |
|---|---|---|---|
| `order-service` | 8081 | `orderdb` | 주문 생성/조회, 결제·재고 오케스트레이션 |
| `payment-service` | 8082 | `paymentdb` | 결제 처리, 외부 PG 호출 (mock) |
| `inventory-service` | 8083 | `inventorydb` | 재고 차감/복구, Redis 캐시 |

## 공통 스택

- Java 21 / Spring Boot 3.x / Gradle (Kotlin DSL — Gradle 빌드 스크립트를 Kotlin 으로 작성하는 모드)
- Spring Web, Data JPA, Validation
- Flyway (DB 마이그레이션 — 스키마 변경을 SQL 파일 시퀀스로 관리)
- Spring Kafka (Phase 2부터)
- Micrometer + OTel (Micrometer 는 메트릭 facade, OTel 은 trace/log 표준)
- Testcontainers (PostgreSQL, Kafka, Redis — 테스트 때 진짜 컨테이너를 띄움)

## Phase 1 생성 가이드

각 서비스 디렉토리에서 Phase 1 작업으로 시작합니다. ADR-001은 현재 Spring Boot 3.x를 기준으로 하므로, Spring Initializr 기본값이 4.x로 바뀌어도 아래 예시는 3.x 라인으로 고정합니다. Phase 1 착수 시점에 Boot 4 전환 여부를 별도 결정한 뒤 생성합니다.

```bash
# Spring Initializr로 시작 (예시)
curl https://start.spring.io/starter.zip \
  -d type=gradle-project-kotlin \
  -d language=java \
  -d javaVersion=21 \
  -d bootVersion=3.5.14 \
  -d groupId=io.minishop \
  -d artifactId=order-service \
  -d dependencies=web,data-jpa,actuator,validation,flyway,postgresql \
  -o starter.zip
unzip starter.zip && rm starter.zip
```

## 서비스 간 통신

- **Phase 1**: 동기 REST 호출 (`order` → `payment` → `inventory`)
- **Phase 2**: Kafka 이벤트로 전환 (`OrderCreated` / `PaymentSucceeded` / `InventoryReserved`)
