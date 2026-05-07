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
- [x] Redisson 분산락 + JPA `@Version` 이중 안전망
- [x] (orderId, productId) 멱등 reserve/release
- [x] `inventory_lock_acquire_seconds{outcome}` 메트릭
- [x] Prometheus scrape 활성화 (8083)

### Step 4 — 동기 REST wiring ✅
- [x] order-service에 PaymentClient + InventoryClient (RestClient + 타임아웃)
- [x] OrderService 오케스트레이션: reserve → pay → on-failure compensate (SAGA 동기 버전)
- [x] HTTP 의미 매핑: 201 PAID / 402 PAYMENT_DECLINED / 409 OUT_OF_STOCK / 502 PAYMENT_INFRA / 503 INVENTORY_INFRA + `X-Order-Outcome` 헤더
- [x] `order_orchestration_seconds{outcome}` 메트릭
- [x] Testcontainers 통합 테스트: happy path / OUT_OF_STOCK 보상 / PAYMENT_DECLINED 보상
- [x] k6 baseline.js → `POST /orders` happy path 시뮬레이션
- [x] README Quick Start end-to-end 데모로 갱신

---

## Phase 2 — Observability 완성 (Step 3b만 남음)

> "장애가 나면 어디가 아픈지 한 화면에서 보인다"

### Step 1 — OTel + Tempo + Loki + Trace↔Log 상관관계 ✅
- [x] OpenTelemetry Spring Boot starter 적용 (3개 서비스)
- [x] OTLP traces/logs export → otel-collector → Tempo/Loki
- [x] `otel.metrics.exporter=none` (Micrometer Prometheus와 이중화 회피)
- [x] logback-spring.xml: 콘솔 패턴에 `[trace_id/span_id]` 인라인 + `OpenTelemetryAppender`
- [x] 테스트에서는 `OTEL_SDK_DISABLED=true`로 export 끔
- [x] README에 trace ↔ log 점프 데모 가이드

### Step 2 — 의미 있는 알람 5개 + Runbook ✅
- [x] `infra/prometheus/alerts.yml` — 5개 룰 (3 그룹: latency-and-errors / runtime-saturation / business)
- [x] alertmanager severity 라우팅 (P1 → critical / P2 → default), inhibit_rules로 P1 발화 시 동일 alertname의 P2 억제
- [x] `docs/runbook/` 5개 (When/Impact/Diagnosis/Mitigation/Post-mortem 포맷)
- [x] alert 룰의 `runbook_url`이 GitHub URL을 가리켜 alertmanager 메시지에서 바로 점프

### Step 3a — Kafka 도입 + Outbox + lifecycle 이벤트 발행 ✅
- [x] spring-kafka 의존성 + producer/consumer 자동 구성
- [x] order-service: Outbox 패턴 (트랜잭션 outbox 테이블 + SKIP LOCKED 폴러)
- [x] order-service: OrderCreated / OrderPaid / OrderFailed 이벤트 (Order TX와 함께 outbox에 기록)
- [x] payment-service: PaymentSucceeded / PaymentFailed 이벤트 (afterCommit publish)
- [x] inventory-service: InventoryReserved / InventoryReleased 이벤트 (lock+TX 끝나고 publish)
- [x] OTel Kafka auto-instrumentation으로 producer span 자동 생성
- [x] producer idempotence + acks=all, consumer 멱등성은 도메인 키로 (ADR-010)

### Step 3b — Kafka choreography로 흐름 자체 비동기화
- [ ] POST /orders → 202 Accepted + outbox에 OrderCreated만 기록 (sync REST orchestration 제거)
- [ ] inventory-service: OrderCreated 소비 → reserve → InventoryReservationSucceeded/Failed
- [ ] payment-service: InventoryReservationSucceeded 소비 → charge → PaymentResult
- [ ] order-service consumer: 이벤트로 Order 상태 전이 + 보상 (PaymentFailed → InventoryRelease 명령)
- [ ] payment/inventory도 outbox로 격상 (ADR-009 후속)

### Step 4 — 첫 트레이스 분석 케이스 스터디 ✅
- [x] 카오스 (`MOCK_PG_LATENCY_MEAN_MS=1500`) → order p99 / 5xx 동시 알람 발화
- [x] Tempo + Loki에서 cross-service trace로 in-doubt 윈도우 식별
- [x] [case-studies/2026-05-07-payment-timeout-race.md](case-studies/2026-05-07-payment-timeout-race.md) 회고 작성 (timeout 단조감소 원칙, 후속 작업 4건 등록)

---

## Phase 3 — 자체 모듈 적용 (3~4주)

> "내가 만든 라이브러리가 운영을 더 편하게 한다"

- [ ] `modules/slow-query-detector` 구현 + publish (Maven local)
- [ ] 서비스에 적용 → 의도적으로 N+1 발생시켜 감지 데모
- [ ] `modules/correlation-mdc-starter` 구현 + 적용
- [ ] `modules/actuator-extras` 구현 + 커스텀 엔드포인트 노출
- [ ] 각 모듈 README에 사용법 + 설계 의도 정리

---

## Phase 4 — Chaos & Case Studies (지속)

> "문제 상황을 만들어보고, 진단하고, 회고로 남긴다"

- [ ] `chaos/` 시나리오 3개 이상 (네트워크 지연, DB 고갈, Pod kill)
- [ ] 케이스 스터디 매월 1~2개 (`case-studies/`)
- [ ] Kind/K3d로 K8s 환경 옵션 추가
- [ ] HPA·PDB·Probe 설계 사례
- [ ] (선택) ArgoCD GitOps 연동

---

## Backlog (우선순위 미정)

- GraalVM Native Image 빌드 옵션
- JIB로 컨테이너 이미지 빌드
- WebFlux 서비스 1개 추가 (vs MVC 비교)
- Virtual Threads on/off 비교 벤치 + 결과 글
- ArgoCD + Argo Rollouts (canary)
- Chaos Mesh 연동
