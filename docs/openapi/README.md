# OpenAPI spec

`commerce-ops` 의 3 마이크로서비스 REST API 를 OpenAPI 3 spec 으로 build-time export 한다.

## 무엇이 들어가나

각 서비스가 독립 Gradle 프로젝트라 spec 도 서비스별로 생성된다.

- `order-service.yaml` — 주문 생성/조회, 결제·재고 오케스트레이션, DLQ 운영 (port 8081)
- `payment-service.yaml` — 결제 처리, mock PG, DLQ 운영 (port 8082)
- `inventory-service.yaml` — 재고 reserve/release, DLQ 운영 (port 8083)

> 이 디렉토리의 `*.yaml` 은 CI 에서 생성·갱신된다. 로컬에서 수기로 편집하지 않는다.

## 생성 방법

각 서비스의 `build.gradle.kts` 에 `org.springdoc.openapi-gradle-plugin` 을 적용했다.
`generateOpenApiDocs` 태스크가 앱을 부팅한 뒤 `/v3/api-docs.yaml` 을 받아
repo 루트의 `docs/openapi/<service>.yaml` 로 저장한다.

```bash
cd services/order-service     && ./gradlew generateOpenApiDocs
cd services/payment-service   && ./gradlew generateOpenApiDocs
cd services/inventory-service && ./gradlew generateOpenApiDocs
```

앱 부팅에 Postgres / Kafka (inventory 는 추가로 Redis) 가 필요하므로,
의존 인프라를 먼저 띄워야 한다. CI 에서는 service container 를 띄운 잡에서
위 태스크를 실행해 산출된 yaml 을 commit 하거나 아티팩트로 업로드한다.

## 보는 법

- Swagger UI — 앱 실행 후 `http://localhost:<port>/swagger-ui.html`
- Redoc — `npx @redocly/cli preview-docs docs/openapi/order-service.yaml`
- 통합 뷰어 — profile repo `ssa1004/ssa1004` 의 `docs/api/index.html` (9 service spec 드롭다운)
