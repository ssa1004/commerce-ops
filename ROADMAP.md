# Roadmap

진행 상황을 체크박스로 관리합니다. 완료 시 `[x]`, 진행 중 `[~]`.

---

## Phase 0 — Scaffold ✅

- [x] 디렉토리 구조 확정
- [x] README / ARCHITECTURE / ROADMAP 작성
- [x] 모듈·서비스 placeholder
- [x] Prometheus 자체 상태를 보는 Grafana infra overview dashboard
- [x] CI workflow 골격
- [x] GitHub 레포 생성 + 첫 푸시

---

## Phase 1 — MVP ✅

> "Compose 한 줄로 서비스가 뜨고 Grafana에 메트릭이 찍힌다"

수직 슬라이스 4-step. 모두 완료.

### Step 1 — order-service vertical ✅
- [x] `order-service` Spring Boot 3 프로젝트 생성 (Gradle Kotlin DSL, Java 21 toolchain)
- [x] Postgres 연결 + Flyway V1 (`orders`, `order_items`)
- [x] `POST /orders`, `GET /orders/{id}` happy path
- [x] Micrometer + Prometheus exporter (`/actuator/prometheus`)
- [x] Testcontainers 통합 테스트
- [x] Prometheus scrape 활성화 (host.docker.internal:8081)
- [x] Grafana JVM+HTTP 대시보드 자동 프로비저닝

### Step 2 — payment-service vertical ✅
- [x] 동일 패턴 (init / Flyway / `POST /payments` / Micrometer / Testcontainers)
- [x] mock-pg 컨트롤러 (latency mean/stddev, failure-rate, timeout-rate)
- [x] PgClient (RestClient + 타임아웃) + 실패 처리 (HTTP 402)
- [x] Prometheus scrape 활성화 (8082)

### Step 3 — inventory-service vertical ✅
- [x] 동일 패턴 (init / Flyway / Micrometer / Testcontainers)
- [x] Redisson 분산락 + JPA `@Version` (낙관적 락) 2개 레이어 — 락이 풀려도 DB 레벨에서 한 번 더 막힘
- [x] (orderId, productId) 멱등 reserve/release — 같은 키로 두 번 와도 한 번 처리한 결과와 동일
- [x] `inventory_lock_acquire_seconds{outcome}` 메트릭 (락 획득 시간 + acquired/timeout/interrupted 분포)
- [x] Prometheus scrape 활성화 (8083)

### Step 4 — 동기 REST wiring ✅
- [x] order-service 에 PaymentClient + InventoryClient (RestClient + 타임아웃)
- [x] OrderService 오케스트레이션: 재고 잡기 → 결제 → 실패 시 보상 (SAGA 의 동기 호출 버전 — 비동기 이벤트 대신 REST 로 묶음)
- [x] HTTP 의미 매핑: 201 PAID / 402 PAYMENT_DECLINED / 409 OUT_OF_STOCK / 502 PAYMENT_INFRA / 503 INVENTORY_INFRA + `X-Order-Outcome` 헤더 (응답 본문이 아닌 헤더에 실패 종류 분류)
- [x] `order_orchestration_seconds{outcome}` 메트릭 (정상/실패별 처리 시간 분포)
- [x] Testcontainers 통합 테스트: 정상 흐름 / 재고 부족 보상 / 결제 거절 보상
- [x] k6 baseline.js → `POST /orders` 정상 흐름 시뮬레이션
- [x] README Quick Start 를 end-to-end 데모로 갱신

---

## Phase 2 — Observability 완성 (Step 3c만 남음)

> "장애가 나면 어디가 아픈지 한 화면에서 보인다"

### Step 1 — OTel + Tempo + Loki + 트레이스 ↔ 로그 상관관계 ✅
- [x] OpenTelemetry Spring Boot starter 적용 (3개 서비스) — 코드 변경 없이 자동 계측
- [x] OTLP (OpenTelemetry 의 표준 송신 프로토콜) 로 트레이스/로그 export → otel-collector → Tempo/Loki
- [x] `otel.metrics.exporter=none` (Micrometer 가 이미 메트릭을 노출하므로 OTel 메트릭과 이중 노출 회피)
- [x] logback-spring.xml: 콘솔 패턴에 `[trace_id/span_id]` 같이 찍기 + `OpenTelemetryAppender` (로그를 OTel 신호로 함께 보내는 어댑터)
- [x] 테스트에서는 `OTEL_SDK_DISABLED=true`로 외부 송신 끔
- [x] README에 trace ↔ log 점프 데모 가이드

### Step 2 — 의미 있는 알람 5개 + 런북 ✅
- [x] `infra/prometheus/alerts.yml` — 5개 룰 (3 그룹: latency-and-errors / runtime-saturation / business)
- [x] alertmanager 심각도 라우팅 (P1 → critical / P2 → default), inhibit_rules (한 알람이 떴을 때 같은 원인의 다른 알람을 일시 억제) 로 P1 발화 시 동일 alertname의 P2 억제
- [x] `docs/runbook/` 5개 (When/Impact/Diagnosis/Mitigation/Post-mortem 포맷)
- [x] 알람 룰의 `runbook_url`이 GitHub URL 을 가리켜 alertmanager 메시지에서 바로 점프

### Step 3a — Kafka 도입 + Outbox + lifecycle 이벤트 발행 ✅
- [x] spring-kafka 의존성 + producer/consumer 자동 구성
- [x] order-service: Outbox 패턴 (이벤트를 별도 테이블에 기록 + SKIP LOCKED 로 여러 폴러가 같은 행을 안 가져감)
- [x] order-service: OrderCreated / OrderPaid / OrderFailed 이벤트 (Order DB 트랜잭션과 같은 트랜잭션에서 outbox 에 기록)
- [x] payment-service: PaymentSucceeded / PaymentFailed 이벤트 (트랜잭션 커밋 직후 발행)
- [x] inventory-service: InventoryReserved / InventoryReleased 이벤트 (락+트랜잭션 끝나고 발행)
- [x] OTel Kafka auto-instrumentation 으로 producer span 자동 생성 (Kafka 발행도 trace 에 한 칸으로 들어감)
- [x] producer 멱등 (재시도로 중복 들어가도 한 번만 저장) + acks=all, consumer 측 멱등성은 도메인 키 (orderId 등) 로 (ADR-010)

### Step 3b — Inbox + Reconciliation (이벤트 받는 쪽 + 부정합 모니터링) ✅
- [x] order-service: `payment_inbox`, `inventory_inbox` 테이블 + UNIQUE 제약으로 멱등 (같은 이벤트가 두 번 와도 한 행) (ADR-011)
- [x] `@KafkaListener` 두 개 (payment.events / inventory.events) — 멱등 upsert (있으면 업데이트, 없으면 INSERT)
- [x] `ReconciliationJob` (정기 스케줄러): 케이스 스터디의 `Order=FAILED ∧ Payment=SUCCESS` 부정합을 카운터로 노출
- [x] `inbox.consume{topic, outcome}`, `reconciliation.inconsistency{kind}` 메트릭

### Step 3c — Kafka choreography 로 흐름 자체 비동기화 (각 서비스가 이벤트만 듣고 자기 일을 한 뒤 다음 이벤트를 발행하는 구조)
- [ ] POST /orders → 202 Accepted + outbox 에 OrderRequested 만 기록 (지금의 동기 REST 오케스트레이션 제거)
- [ ] inventory-service: OrderRequested 소비 → reserve → InventoryReservationSucceeded/Failed 발행
- [ ] payment-service: InventoryReservationSucceeded 소비 → charge → PaymentResult 발행
- [ ] order-service consumer: 이벤트로 Order 상태 전이 + 보상 (PaymentFailed → InventoryRelease 명령)
- [ ] payment/inventory 도 outbox 로 격상 (ADR-009 후속)

### Step 4 — 첫 트레이스 분석 케이스 스터디 ✅
- [x] 카오스 (`MOCK_PG_LATENCY_MEAN_MS=1500`) → order p99 / 5xx 동시 알람 발화
- [x] Tempo + Loki에서 cross-service trace로 in-doubt 윈도우 식별
- [x] [case-studies/2026-05-07-payment-timeout-race.md](case-studies/2026-05-07-payment-timeout-race.md) 회고 작성 (timeout 단조감소 원칙, 후속 작업 4건 등록)

---

## Phase 3 — 자체 운영 라이브러리 (`modules/`)

> 운영에서 반복되는 보일러플레이트를 Spring Boot starter 로 떼어내는 단계

### Step 1 — slow-query-detector v0.1 ✅
- [x] Spring Boot starter 골격 (auto-config — 의존성만 추가하면 자동 활성화 + properties)
- [x] DataSource 를 datasource-proxy 로 감싸는 BeanPostProcessor (Spring 이 만든 bean 을 후처리하는 훅 — 여기서 DataSource 를 프록시로 교체) (ADR-012)
- [x] 슬로우 쿼리 감지 — `slow_query_total` 카운터 + WARN 로그
- [x] N+1 감지 (한 번의 작업에서 같은 모양의 쿼리가 N 번 반복되는 안티패턴 — JPA lazy loading 으로 자주 발생). 정규화 SQL (리터럴을 `?` 로 치환해 모양 비교) + ThreadLocal + 트랜잭션 종료 후 정리. `n_plus_one_total` 카운터
- [x] `query_execution_seconds{outcome=ok|slow}` 타이머 (모든 쿼리의 실행 시간 분포)
- [x] 13개 단위 테스트 (정규화 / 리스너 동작 / Spring wiring)
- [x] mavenLocal publish (`io.minishop:slow-query-detector:0.1.0-SNAPSHOT`)
- [x] [README](modules/slow-query-detector/README.md) + [DESIGN.md](modules/slow-query-detector/DESIGN.md)

### Step 2 — slow-query-detector 를 서비스에 적용 + N+1 데모 ✅
- [x] order-service settings.gradle.kts 에 `includeBuild("../../modules/slow-query-detector")` — composite build (여러 Gradle 프로젝트를 하나처럼 묶어 빌드) 로 mavenLocal publish 없이 로컬·CI 동일하게 빌드
- [x] order-service build.gradle.kts 에 `implementation("io.minishop:slow-query-detector")`
- [x] `GET /orders` (paging) — 일부러 N+1 이 나는 단순 listing 구현 (응답 직렬화 시점에 items 가 lazy load 됨)
- [x] 통합 테스트: 주문 5건 생성 + listing → `n_plus_one_total` 카운터 증가 검증
- [x] Grafana "Slow Query & N+1" 대시보드 자동 프로비저닝 (5개 패널)
- [x] 알람 룰: `n_plus_one_detected` (P2) + [런북](docs/runbook/n-plus-one-detected.md)

### Step 3 — correlation-mdc-starter
- [ ] OTel trace_id 가 이미 MDC (Mapped Diagnostic Context — SLF4J/Logback 의 thread-local 키밸류 저장소, 로그 패턴에서 `%X{key}` 로 출력 가능) 에 들어가는 부분은 그대로 활용
- [ ] 추가: HTTP 헤더(X-User-Id, X-Request-Id 등)에서 비즈니스 식별자를 MDC 로 주입 (로그/트레이스에 사용자 ID 까지 같이 보이도록)
- [ ] Kafka consumer 에서도 동일하게 (현재 trace 컨텍스트 외에 추가 attribute)
- [ ] 비동기 Executor 데코레이터 (Reactor / `@Async`) — 다른 스레드로 작업이 넘어갈 때도 MDC 가 따라가도록

### Step 4 — actuator-extras
- [ ] `/actuator/hikari` (DB 커넥션 풀의 active/idle/pending + 최근 느렸던 acquire 기록)
- [ ] `/actuator/threadpools` (모든 `ThreadPoolTaskExecutor` 의 현재 상태)
- [ ] `/actuator/transactions` (지금 진행 중인 트랜잭션 추적)

### Step 5 — chaos-injector
- [ ] AOP (Aspect-Oriented Programming — 메서드 호출 앞뒤에 별도 로직을 끼워 넣는 기법) 기반 메서드 단위 지연/실패 주입
- [ ] HTTP endpoint 로 동적 조절 (재시작 없이 강도 변경)
- [ ] `production` 프로파일 (Spring 의 환경 구분) 에서 강제 disable 안전 가드

---

## Phase 4 — Chaos & Case Studies (지속)

> "문제 상황을 만들어보고, 진단하고, 회고로 남긴다"

- [ ] `chaos/` 시나리오 3개 이상 (네트워크 지연, DB 커넥션 고갈, Pod kill — 컨테이너 강제 종료)
- [ ] 케이스 스터디 매월 1~2개 (`case-studies/`)
- [ ] Kind/K3d 로 로컬 K8s 환경 옵션 추가
- [ ] HPA (Horizontal Pod Autoscaler — 트래픽에 따라 Pod 수를 자동 증감) · PDB (Pod Disruption Budget — 노드 점검 시 동시 종료 가능 수 제한) · Probe (liveness/readiness — 컨테이너의 살아있음/요청 받을 준비됨 체크) 설계 사례
- [ ] (선택) ArgoCD GitOps (Git 저장소 상태를 클러스터에 자동 반영) 연동

---

## Backlog (우선순위 미정)

- GraalVM Native Image (JVM 대신 사전 컴파일된 네이티브 바이너리로 띄우는 옵션 — 시작 빠름) 빌드 옵션
- JIB 로 컨테이너 이미지 빌드 (Docker daemon 없이 Java 빌드 도구만으로 이미지 생성)
- WebFlux (논블로킹 리액티브 스택) 서비스 1개 추가 (vs MVC — 전통적 동기 스택 비교)
- Virtual Threads on/off 비교 벤치 + 결과 글
- ArgoCD + Argo Rollouts (canary — 새 버전을 일부 트래픽에만 먼저 흘리는 점진 배포)
- Chaos Mesh (K8s 위에서 장애를 주입하는 도구) 연동
