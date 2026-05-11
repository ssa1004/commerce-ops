# Services

세 개의 Spring Boot 마이크로서비스. 각 서비스는 독립 Gradle 프로젝트로, root 에 settings.gradle 이 없고 `./gradlew` 가 서비스 디렉토리 안에 있습니다.

| Service | Port | DB | 책임 |
|---|---|---|---|
| [`order-service`](order-service/) | 8081 | `orderdb` | 주문 생성/조회, 결제·재고 동기 오케스트레이션, Outbox, Inbox + reconciliation, SAGA StateMachine (shadow) |
| [`payment-service`](payment-service/) | 8082 | `paymentdb` | 결제 처리, mock PG 호출, afterCommit publish |
| [`inventory-service`](inventory-service/) | 8083 | `inventorydb` | 재고 reserve/release, Redisson 분산락 + JPA `@Version` 낙관적 락, 멱등 키 (orderId, productId) |

서비스별 깊은 설계는 각 디렉토리의 README 참고. 전체 흐름은 [../ARCHITECTURE.md](../ARCHITECTURE.md), 결정 배경은 [../docs/decision-log.md](../docs/decision-log.md).

## 공통 스택

- Java 21 / Spring Boot 3.5 / Gradle (Kotlin DSL)
- Spring Web, Data JPA, Validation, Actuator
- Flyway (DB 마이그레이션 — 스키마 변경을 SQL 파일 시퀀스로 관리)
- Spring Kafka
- Micrometer + OpenTelemetry Spring Boot starter (Micrometer 는 메트릭 facade, OTel 은 trace/log 표준)
- Testcontainers (PostgreSQL, Kafka, Redis — 테스트 때 진짜 컨테이너를 띄움)
- `modules/` 의 자체 starter (`slow-query-detector`, `jfr-recorder-starter`, `correlation-mdc-starter`) 를 composite build (`includeBuild("../../modules/<name>")`) 로 참조 — mavenLocal publish 없이 로컬·CI 동일하게 빌드

## 빌드 / 테스트

```bash
# 각 서비스 디렉토리에서
cd services/order-service && ./gradlew build check
cd services/payment-service && ./gradlew build check
cd services/inventory-service && ./gradlew build check
```

CI 매트릭스 (`.github/workflows/ci.yml`) 가 services/* 와 modules/* 를 각각 빌드합니다.

## 서비스 간 통신

- **현재 (Phase 2 까지)**: 동기 REST (`order` → `inventory`, `order` → `payment`) + Kafka 이벤트 (`order.events` / `payment.events` / `inventory.events`) 비동기 알림 병행
- **향후 (Phase 2 Step 3c)**: choreography (각 서비스가 이벤트만 듣고 자기 일 처리 + 다음 이벤트 발행) 로 흐름 자체 비동기화 — [ROADMAP](../ROADMAP.md#step-3c--kafka-choreography-로-흐름-자체-비동기화-각-서비스가-이벤트만-듣고-자기-일을-한-뒤-다음-이벤트를-발행하는-구조) 참조
