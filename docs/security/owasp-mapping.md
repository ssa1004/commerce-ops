# OWASP API Security Top 10 (2023) — 매핑 & sweep

본 문서는 [OWASP API Security Top 10 (2023)](https://owasp.org/API-Security/editions/2023/en/0x00-introduction/) 의 10 개 항목을 현재 레포 (services × 3 + modules × 5 + infra) 와 한 줄씩 매핑한다.

**전제 — 학습용 레포**: 본 저장소는 [SECURITY.md](../../SECURITY.md) 가 밝힌 대로 *포트폴리오 / 학습* 목적이다. 인증 게이트웨이가 없고 (`services/*` 어느 것도 Spring Security 를 import 하지 않는다), `infra/*` 의 옵저버빌리티 스택 비밀번호·앵커는 데모 placeholder 다. **운영 배포를 가정하지 않는다.** 그 위에서 — *그래도* 어떤 항목이 currently 안전하고, 어떤 항목이 *알려진 갭* 이며, 어떤 항목은 *이미 적극 가드* 가 있는지를 한 페이지에 모은 게 본 문서의 목적이다.

각 행의 상태 표기:

- **OK** — 현재 코드가 해당 위험을 명시적으로 차단/완화.
- **GAP (intentional)** — 학습 레포 범위에서 의도적으로 비워둠 (인증/인가 등). ROADMAP 항목.
- **GUARDED (shape)** — *모양 단계* (input validation / cap 등) 의 가드가 있고, 비즈니스 보호 레이어는 후속 단계.

---

## 한눈 요약

| # | 항목 | 상태 | 비고 |
|---|---|---|---|
| API1 | Broken Object Level Authorization (BOLA) | **GAP (intentional)** | `GET /orders/{id}` 등에 호출자–소유자 검증 없음 |
| API2 | Broken Authentication | **GAP (intentional)** | 서비스 자체엔 인증 없음. `infra/auth-stub` 는 *검증 시연용 JWK Set* 만 |
| API3 | Broken Object Property Level Auth (BOPLA) | **GUARDED (shape)** | DTO 응답에 PII / 신용카드 번호 없음. `failureReason` 만 vendor string |
| API4 | Unrestricted Resource Consumption | **OK** | order / inventory DTO 에 list-size / quantity cap, `GET /orders?size=...` `@Min(1) @Max(100)`, adaptive limiter |
| API5 | Broken Function Level Authorization | **GAP (intentional)** | admin endpoint 없음 (학습 범위) — `/actuator/jfr` 의 write op 만 운영 가드 필요 |
| API6 | Unrestricted Access to Sensitive Business Flows | **GAP (intentional)** | bot / 트래픽 패턴 검출 없음. 대신 adaptive limiter 가 cascade 측면만 차단 |
| API7 | Server Side Request Forgery (SSRF) | **OK** | 외부 호출 URL 모두 `@ConfigurationProperties` 의 *고정* 값. 사용자 입력으로 URL/host 가 생성되는 경로 없음 |
| API8 | Security Misconfiguration ⭐ | **GUARDED (shape)** | 가장 관련 — actuator 노출 화이트리스트, Grafana/Loki/Tempo 의 dev-only 노출, helm `containerSecurityContext` (non-root + readOnlyRootFs + drop ALL) |
| API9 | Improper Inventory Management | **OK** | v1/v2 가 *없음* — 단일 버전. helm ingress 의 `/api/v1/...` 는 *향후* prefix 자리 (현 controllers 는 `/orders`, `/payments`, `/inventories`). dev–prod 의 차이는 [ARCHITECTURE.md](../../ARCHITECTURE.md) 와 helm `values-prod.yaml` 에 명시 |
| API10 | Unsafe Consumption of APIs | **GUARDED (shape)** | PG 응답 (`PgChargeResponse`) DTO 로 deserialize + status 화이트리스트 + `read-timeout` 명시. PG vendor 문자열은 *원문* 으로 저장 (`failureReason` / `externalRef`) — 추가 sanitize 는 후속 |

---

## 항목별 상세

### API1 — Broken Object Level Authorization (BOLA)

> 다른 사용자의 자원에 접근?

**스코프 — 본 레포의 모든 외부 GET 엔드포인트**

| Service | Endpoint | 소유자 검사 |
|---|---|---|
| order-service | `GET /orders/{id}` — `OrderController#getById` | **없음** — id 만 알면 누구나 조회 |
| order-service | `GET /orders` — listRecent | **없음** — 모든 사용자의 최근 주문 (size 1~100) |
| payment-service | `GET /payments/{id}` — `PaymentController#getById` | **없음** |
| inventory-service | `GET /inventories/{productId}` — `InventoryController#get` | productId 는 *카탈로그* 자원이라 user-level 소유 개념이 약함 |

**상태 — GAP (intentional)**.

**이유** — 본 레포는 인증 / 인가 게이트웨이 (Spring Security + JWT) 를 *의도적으로* 도입하지 않는다. 트레이싱 / 메트릭 / 옵저버빌리티 데모가 본 레포의 목적이고, 인증은 sister repo (`auth-service`) 의 책임. 그 결과 BOLA 도 검사할 *호출자 ID* 자체가 없다. [infra/auth-stub/README.md](../../infra/auth-stub/README.md) 가 통합 데모 시 sister repo 가 JWT 검증을 *시연* 하는 자리 (JWK Set) 만 제공.

**후속 단계**:
1. ResourceServer 활성 (`spring-boot-starter-oauth2-resource-server`) + `jwk-set-uri` = auth-stub
2. controller 에 `@AuthenticationPrincipal Jwt` → `sub` 를 비교: `if (!order.getUserId().equals(jwt.getClaimAsString("sub"))) throw new AccessDeniedException(...)`
3. `GET /orders` (listRecent) 는 *호출자 본인 주문* 으로 필터링

---

### API2 — Broken Authentication

> 토큰 검증 / 세션 관리 / brute-force 방어?

**상태 — GAP (intentional)**.

**현황**:
- `services/*` 어느 서비스도 Spring Security 의존성을 갖지 않음 — `grep -r 'spring-security' services/*/build.gradle.kts` 가 0 라인.
- `infra/auth-stub/` 는 *정적 JWK Set + OIDC discovery* 만 노출 (`nginx` 컨테이너 — 데모 키 1 개, kid=minishop-demo-2026-05). 본 레포의 서비스가 이 JWK 로 토큰을 *발행* 하지 않음 (`jwks.json` 의 짝 private 키는 git 에 없음).
- `scripts/integration-demo.sh` 는 trace 전파 시연 목적의 header decoration 용 *dummy JWT* 만 붙임 — 검증 없음.

**후속 단계** — API1 항목과 같이 묶음. JWT 검증을 ResourceServer 로 활성하면 BOLA 와 Broken Auth 가 동시에 해소.

---

### API3 — Broken Object Property Level Authorization (BOPLA)

> 응답 DTO 가 *조회자가 봐선 안 되는* 필드를 흘리는가?

**스코프 — `web/dto/*Response.java`**

| DTO | 노출 필드 | 평가 |
|---|---|---|
| `order-service/.../OrderResponse` | id, userId, status, totalAmount, items, createdAt, updatedAt | **안전** — userId 가 들어있으나 자기 자신의 주문을 보는 정상 응답. 인증 도입 시 *다른 사용자가 이 응답을 받지 못하게* (= BOLA) 가 1차 방어선. |
| `order-service/.../OrderItemResponse` | id, productId, quantity, price | **안전** — 비즈니스 식별자만 |
| `payment-service/.../PaymentResponse` | id, orderId, userId, amount, status, externalRef, **failureReason**, attempts, createdAt, completedAt | **주의** — `failureReason` 은 PG 의 원문 (예: `"simulated timeout"`, `"PG returned 503 Service Unavailable"`). vendor 내부 메시지 노출 위험은 있으나 *카드 번호 / 인적 식별자* 가 들어가지 않는 mock 환경 |
| `inventory-service/.../InventoryResponse` | productId, availableQuantity | **안전** — 카탈로그 자원 |
| `inventory-service/.../ReservationResponse` | id, productId, orderId, quantity, status, ... | **안전** |

**상태 — GUARDED (shape)**.

**이유** — DTO 는 모두 `record` 로 *명시적* 노출 필드만 직렬화 (entity 직접 노출 안 함). 신용카드 번호 / 비밀번호 / 토큰은 어디에도 없음. `failureReason` 의 vendor string 만 후속 sanitize 자리.

**구체 가드**:
- `domain/*` 의 JPA entity 는 어떤 controller 도 직접 반환하지 않음 — 항상 `*Response.from(entity)` 변환.
- request DTO 도 `record` — 알 수 없는 필드 (`mass assignment`) 는 `@JsonIgnoreProperties` 의 Spring Boot default 동작에 따라 ignore (Jackson 의 `FAIL_ON_UNKNOWN_PROPERTIES=false` default).

**후속 단계** — `failureReason` 을 enum 화 (`PgFailureCode.TIMEOUT / PG_5XX / DECLINED`) + vendor string 은 별도 audit log 로 분리.

---

### API4 — Unrestricted Resource Consumption

> 한 호출이 N 개의 fan-out / 무한 page / 무한 batch 를 일으키는가?

**스코프 — listing / batch 가능 경로 + adaptive limiter**

**현황** (본 sweep 의 핵심 fix 자리):

| 경로 | 보호 |
|---|---|
| `POST /orders` 의 `items` 길이 | **NEW** `@Size(max=50)` — 한 호출이 50 개 초과 reserve fan-out 못 함 |
| `POST /orders` 의 `quantity` | **NEW** `@Max(1000)` — 단일 SKU 가 한 번에 가용 재고 0 으로 떨어뜨리는 시나리오 차단 |
| `POST /inventories/reserve` 의 `quantity` | **NEW** `@Max(1000)` — order-service 우회 직접 호출에도 동일 cap |
| `GET /orders?size=...` | `@Min(1) @Max(100)` — 기존 |
| order-service 의 outbound (inventory / payment) | adaptive limiter (Gradient2) — 한도 초과 시 즉시 `LimitExceededException` → 503 + Retry-After |
| HikariCP `leak-detection-threshold` | order=30s / payment=20s / inventory=30s — connection 누수 조기 검출 |
| Kafka consumer `max.poll.records=50` + `max.poll.interval.ms=300s` | 한 poll 의 처리 시간 상한 명시 |

**상태 — OK** (이 sweep 의 fix 이후).

**fix 전 상태**: `CreateOrderRequest.items` 는 `@NotEmpty` 만 — 한 주문에 수천 개의 item 을 실어 보내면 inventory.reserve 가 N 번 직렬 호출 + adaptive limiter 한도까지 한 주문이 다 먹어버림. **이 sweep 에서 `@Size(max=50)` 추가**.

---

### API5 — Broken Function Level Authorization

> admin / internal-only 기능에 인가 없이 접근되는가?

**스코프 — admin / write 성격의 경로**

| 경로 | 노출 | 평가 |
|---|---|---|
| `POST /actuator/jfr/{tag}` — JFR ad-hoc dump | actuator endpoint, 운영자 동작 | **운영 환경에서 노출 시 위험** — `tag` 가 파일명에 끼워지는데 `JfrRecorder#sanitize` 가 `[^a-zA-Z0-9_-]` 를 `_` 로 치환 + 길이 32 cap → path traversal 차단. 다만 *발견* 자체가 운영자만 해야 함 |
| `POST /actuator/jfr/{tag}` 의 access 가드 | `@ConditionalOnAvailableEndpoint` — `management.endpoints.web.exposure.include` 에 `jfr` 가 *명시될 때만* bean 등록 | 1차 방어. 현재 `order-service/application.yml` 에 `jfr` 가 *명시* 노출됨 (`include: health, info, prometheus, metrics, jfr`) |
| `GET /mock-pg/config` — payment-service | mock PG 의 latency/failure rate 설정 — 진짜 비밀이 아님 | **dev only** — `mini-shop.mock-pg.enabled` 가 false 면 controller 자체가 등록 안 됨 (`@ConditionalOnProperty`) — 운영에선 false 로 둘 것 |
| `POST /inventories/release` | order-service 의 보상 호출 — 호출 자격 가드 없음 | **GAP** — 인증 도입 시 *order-service의 service account* 만 호출 가능하게 mTLS / 토큰 차원 |

**상태 — GAP (intentional)** (관리자 자체가 없는 학습 범위) **+ OK** (path traversal 등 표면 위험 차단).

**구체 가드**:
- `JfrRecorder#sanitize` — file name safe 문자만 허용 + 32 cap.
- `MockPgController#@ConditionalOnProperty(matchIfMissing = true)` 의 default true 는 *dev 편의* — 운영에선 helm `MOCK_PG_ENABLED=false` 로 끔.

**후속 단계** — `/actuator/jfr` 류는 `management.server.port` 분리 + `IpAccessControlFilter` (예: 인프라 망 CIDR 만 허용) — 단순한 운영 패턴.

---

### API6 — Unrestricted Access to Sensitive Business Flows

> bot 이 한 사람당 1 회 의도의 흐름 (예: 한정 상품 주문) 을 N 회 돌리는가?

**상태 — GAP (intentional)**.

**현황** — bot 검출 / device fingerprint / CAPTCHA 등은 본 레포의 범위가 아님. 다만 cascade 측면에서는:

- adaptive limiter (Gradient2) 가 *upstream 이 느려지면* 한도를 줄여 backend 보호.
- HikariCP 의 `connection-timeout` 이 짧게 (2~3s) 잡혀 풀 saturation 이 503 으로 빠르게 표면화.
- order-service 의 `OrderEvent` outbox + 멱등 키 (orderId+productId) — 같은 주문의 reserve 가 중복 차감 안 됨.

이건 *flow 보호* 가 아니라 *cascade 차단* — OWASP API6 의 직접 답은 아님.

**후속 단계** — 비즈니스가 정해지면 (예: 한정 1 인 1 주문) 그 흐름에 dedicated rate-limit + 같은 user 의 동시 inflight order 차단 등.

---

### API7 — Server Side Request Forgery (SSRF)

> 외부 호출 URL 의 host/port 가 사용자 입력으로 결정되는가?

**스코프 — `*Client` 류 + outbound RestClient + JFR uploader endpoint**

| 호출 | URL 결정 | SSRF 가능성 |
|---|---|---|
| `order-service` → inventory | `InventoryClientProperties.url` (env `INVENTORY_URL` / config) — 고정 | **없음** — 사용자 입력으로 host 가 들어오지 않음 |
| `order-service` → payment | `PaymentClientProperties.url` — 고정 | **없음** |
| `payment-service` → PG | `PgProperties.url` — `mini-shop.pg.url` 에서 주입 — env / config 고정 | **없음** — Phase 1 에서는 자기 자신의 mock-pg 호출 |
| `payment-service` → mock-pg internal | `MockPgController.@RequestMapping("/mock-pg")` — same-host | **없음** |
| `JFR uploader` → S3/MinIO | `JfrUploadProperties.endpoint` — env / config 고정 | **없음** — endpoint override 는 운영 admin 만 설정. 단 `endpoint` 가 *misconfig* 되면 임의 호스트로 PUT 가능 — 운영 자격증명 (`accessKey/secretKey`) 도 같이 흘러갈 위험. 실 배포는 IAM role 만 권장 ([JFR uploader](../../modules/jfr-recorder-starter/src/main/java/io/minishop/jfr/upload/JfrUploadProperties.java) 의 javadoc 참조) |

**상태 — OK**.

**구체 가드**:
- `RestClient.builder().baseUrl(props.url())` — *baseUrl 자체* 가 ConfigurationProperties 에서 옴. 사용자 요청 body 가 `props.url()` 을 결정하는 경로 없음.
- `RestClient.post().uri("/inventories/reserve")` — *상대 경로*. host 는 baseUrl 고정.

**유일한 callback 형태**: alertmanager `webhook_configs.url` (`infra/alertmanager/config.yml`) — Prometheus → Alertmanager → outbound webhook. 본 레포 코드와 별개 (alertmanager 자체 동작).

---

### API8 — Security Misconfiguration ⭐

> 운영 보안의 *습관* — actuator / management 노출, 옵저버빌리티 백엔드의 인증, 기본 비밀번호, TLS 강제, security header 등.

**본 sweep 의 중심 항목**.

#### 8.1 Spring Boot actuator 노출

| Service | `management.endpoints.web.exposure.include` | 평가 |
|---|---|---|
| order-service | health, info, prometheus, metrics, **jfr** | jfr 가 write op (`POST /actuator/jfr/{tag}`) 포함 — 운영 환경 노출 시 운영자 외 접근 차단 필요 |
| payment-service | health, info, prometheus, metrics | 표준 — 운영 가드는 같은 패턴 (망 격리 + ACL) |
| inventory-service | health, info, prometheus, metrics | 동일 |

**가드**:
- `management.endpoint.health.show-details: when-authorized` — Spring Security 미설정 환경에서 `when-authorized` 는 fallback 으로 *NEVER show* (안전 측 default).
- `info` endpoint — 본 레포는 `git.properties` / `build-info` 를 생성하지 않음 → `git.commit.id` 등 *경로 추정* 자료 노출 없음 (사실상 빈 응답).
- helm `containerSecurityContext` 에서 `allowPrivilegeEscalation: false` + `readOnlyRootFilesystem: true` + `capabilities.drop: [ALL]` + `runAsNonRoot: true`.

**후속 단계** — `management.server.port: 9081` 분리 + ingress 에서 `/actuator/*` 외부 노출 차단. helm `templates/ingress.yaml` 의 주석이 이미 "actuator 는 외부 노출하지 않는 게 원칙" 을 명시.

#### 8.2 옵저버빌리티 스택의 인증

| 컴포넌트 | dev 노출 | 평가 |
|---|---|---|
| Prometheus (`prometheus:9090`) | 인증 없음 | **dev only** — 메트릭에 비즈니스 식별자 (예: `application=order-service`) 만, PII 없음 |
| Grafana (`grafana:3000`) | `GF_AUTH_ANONYMOUS_ENABLED: "true"` + `admin/admin` | **dev only** — 운영은 `GF_AUTH_ANONYMOUS_ENABLED=false` + OIDC / LDAP 필수 |
| Loki (`loki:3100`) | `auth_enabled: false` | **dev only** — 운영은 multi-tenant + `X-Scope-OrgID` 강제 |
| Tempo (`tempo:3200`) | 인증 없음 | **dev only** — 사고 trace 가 PII 를 포함할 수 있어 ACL 필수 |
| Alertmanager (`alertmanager:9093`) | 인증 없음 | **dev only** — silence / route 변경 가능 |
| OTel Collector (`otel-collector:4317/4318`) | 인증 없음 | **dev only** — 운영은 mTLS / 토큰 |
| auth-stub (`auth-stub:9000`) | static JWK Set | **공개 가능** — public key 의 본래 성격 |

**상태 — GUARDED (shape) + 운영은 dev 노출 그대로 안 됨**.

**가드** (현재 코드에 들어가 있는 것):
- `infra/docker-compose.yml` 의 모든 backend 가 *같은 docker network* 안에서만 통신 — 호스트 포트 forward 는 *dev 편의*. 운영 helm 에서는 ClusterIP / Service mesh.
- helm `networkPolicy.yaml` (운영에서만 enable) — order-service ↔ payment / inventory + 같은 ns 의 ingress controller + observability ns 의 prometheus scrape 만 허용.

**후속 단계** — 운영용 helm 에 `monitoring-server` (Grafana / Prometheus) 의 OIDC 통합 노트 + Loki/Tempo 의 `auth_enabled: true` + multi-tenant header 강제.

#### 8.3 비밀번호 / TLS / Security header

- 모든 `application.yml` 의 DB 비밀번호 = `mini` (placeholder). 운영은 helm `secret.create=false` + `extraEnvFrom` 로 ExternalSecret 참조.
- TLS — helm `values-prod.yaml` 의 `ingress.tls` + `cert-manager.io/cluster-issuer: letsencrypt-prod` 명시.
- HSTS / X-Frame-Options / CSP — application 측에서 직접 set 안 함. ingress (`nginx`) 에서 default 헤더 + annotation 으로 추가 권장.

---

### API9 — Improper Inventory Management

> v1/v2 가 존재하는데 v1 의 deprecation / 보안 패치가 누락되는가?

**스코프 — API 버전 + 환경 구분**

| 항목 | 현황 |
|---|---|
| API 버전 | **단일 버전** — `/orders`, `/payments`, `/inventories`. v1/v2 분기 없음. v1 deprecated 도 없음. |
| Helm ingress prefix | `helm/.../ingress.yaml` 와 `values-prod.yaml` 의 `/api/v1/orders` 등은 *향후* 자리 (controller 가 아직 `/orders`) — 운영 배포 시 ingress 가 `/api/v1/orders` → `/orders` rewrite 하거나 controller 가 `/api/v1` prefix 를 추가 |
| 환경 구분 | `dev` (compose) ↔ `prod` (`values-prod.yaml`) 가 명확히 분리. `deployEnv` env 가 OTel resource attribute `deployment.environment` 로 전파 — 트레이스에서 환경별 분리 가능 |
| Service catalog | [catalog-info.yaml](../../catalog-info.yaml) — Backstage 표준. 3 서비스 + 5 모듈 + 1 컴포넌트 (auth-stub) 명시 |
| 미배포 컴포넌트 | `modules/actuator-extras` 와 `modules/chaos-injector` 는 *설계 단계* (README only) — 의존성으로 추가해도 동작 없음. ROADMAP Phase 3 Step 8/9 자리 |

**상태 — OK** (학습 레포의 단순 단일 버전 + 환경 구분 명시 + catalog 표준).

**후속 단계** — `/api/v1/...` 실 도입 시 controller 에 `@RequestMapping("/api/v1/orders")` 로 변경 또는 ingress rewrite.

---

### API10 — Unsafe Consumption of APIs

> 3rd party (PG, 외부 API) 응답을 *그대로 믿어* 시스템에 흘려보내는가?

**스코프 — `PgClient` (payment vendor) + 외부 callback**

| 경로 | 가드 |
|---|---|
| `payment-service` → PG (`PgClient`) | 1) Jackson deserialize 으로 schema 검증 (`PgChargeResponse` record) — 모르는 필드는 ignore. 2) `onStatus(isError, ...)` 화이트리스트 — 4xx/5xx 면 즉시 throw. 3) `read-timeout=5s` — `RestClient` 의 `SimpleClientHttpRequestFactory` 에 명시. 4) `ResourceAccessException` → `PgFailureException` 으로 통일된 매핑 |
| `PgChargeResponse.reference` (외부 ID) | `Payment.externalRef` 에 그대로 저장 — `String` 타입. 길이 / 문자 cap 없음 — 향후 `@Size(max=64)` + 알파벳/숫자/-_ 화이트리스트 권장 |
| `PgChargeResponse.reason` (failure string) | `Payment.failureReason` 에 그대로 저장 → `PaymentResponse.failureReason` 로 echo. *원문 vendor 메시지가 클라이언트에 노출* — API3 항목에서 언급한 sanitize 자리 |
| Kafka consumer (order-service: payment.events / inventory.events) | `InboundPaymentEvent` / `InboundInventoryEvent` DTO 로 deserialize — schema 가드. Inbox 패턴으로 중복 처리 차단 ([ADR-018 inbox]) |

**상태 — GUARDED (shape)**.

**구체 가드**:
- `read-timeout=5s` — in-doubt window 차단 ([case-studies/2026-05-07-payment-timeout-race.md](../../case-studies/2026-05-07-payment-timeout-race.md) 의 회고).
- adaptive limiter 가 PG 쪽 latency 가 늘어나면 동시 진행 한도를 줄여 다음 호출자를 `LimitExceededException` 으로 즉시 거절.
- retry interceptor 의 exponential backoff + jitter — thundering herd 차단.

**후속 단계** — `externalRef` 의 입력 sanitize + `failureReason` 의 enum 화 (API3 항목과 묶음).

---

## sweep 결과 — 변경 사항

본 sweep 에서 *코드 변경* 으로 닫은 항목:

1. **API4** — `CreateOrderRequest.items` 에 `@Size(max=50)`, `CreateOrderItemRequest.quantity` 에 `@Max(1000)`, `ReserveRequest.quantity` 에도 동일 cap. 한 호출이 N 개 fan-out 또는 단일 SKU 의 가용 재고를 0 으로 떨어뜨리는 시나리오 차단.

본 sweep 에서 *문서화* 로 표기한 항목 (intentional GAP / 후속 단계):

- API1 / API2 / API5 / API6 — 인증 게이트웨이 도입과 묶여 후속 단계. sister repo `auth-service` 와의 통합에서 해소.
- API3 / API10 의 `failureReason` enum 화 — 비즈니스 결정 (vendor 별 매핑 표) 이 필요해 별도 ADR 자리.
- API8 의 운영 helm 보강 (actuator 분리 포트 / Loki multi-tenant) — 운영 배포 시 자리.

## 참고

- [SECURITY.md](../../SECURITY.md) — 학습 레포 안내 + 취약점 보고 경로
- [ARCHITECTURE.md](../../ARCHITECTURE.md) — 시스템 구성
- [docs/decision-log.md](../decision-log.md) — ADR-008 (Prometheus pull) / ADR-018 (Inbox) / ADR-021 (Kafka consumer) / ADR-022 (retry+limiter) 등
- [infra/auth-stub/README.md](../../infra/auth-stub/README.md) — JWK Set stub 의 의도
- [helm/mini-shop/values-prod.yaml](../../helm/mini-shop/values-prod.yaml) — 운영 override
