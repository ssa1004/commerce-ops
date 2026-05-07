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

## Phase 2 — Observability 완성

> "장애가 나면 어디가 아픈지 한 화면에서 보인다"

### Step 1 — OTel + Tempo + Loki + Trace↔Log 상관관계 ✅
- [x] OpenTelemetry Spring Boot starter 적용 (3개 서비스)
- [x] OTLP traces/logs export → otel-collector → Tempo/Loki
- [x] `otel.metrics.exporter=none` (Micrometer Prometheus와 이중화 회피)
- [x] logback-spring.xml: 콘솔 패턴에 `[trace_id/span_id]` 인라인 + `OpenTelemetryAppender`
- [x] 테스트에서는 `OTEL_SDK_DISABLED=true`로 export 끔
- [x] README에 trace ↔ log 점프 데모 가이드

### Step 2 — 의미 있는 알람 5개 + Runbook
- [ ] `infra/prometheus/alerts.yml` 채우기:
  - order p99 latency high
  - order error rate spike (5xx만)
  - HikariCP pool saturation
  - JVM GC pause too long
  - inventory_lock_acquire timeout 비율 높음
- [ ] alertmanager 라우팅 (webhook/slack 자리표)
- [ ] `docs/runbook/` 알람별 5개 작성

### Step 3 — Kafka 비동기 전환
- [ ] OrderCreated / PaymentResult / InventoryReserved 이벤트
- [ ] order-service Outbox 패턴 (가벼운 버전)
- [ ] payment / inventory consumer로 흐름 전환
- [ ] OTel auto-instrumentation으로 Kafka span 자동 전파 확인

### Step 4 — 첫 트레이스 분석 케이스 스터디
- [ ] 카오스 1개 (예: `MOCK_PG_LATENCY_MEAN_MS=1500`)
- [ ] 트레이스 분석 → `case-studies/` 회고 1편

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
