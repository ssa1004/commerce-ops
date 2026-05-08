# Decision Log

설계 결정의 *이유*를 짧게 기록합니다 (가벼운 ADR — Architecture Decision Record).
포맷: 결정 / 배경 / 대안 / 결과.

---

## ADR-001 — Java 21 + Spring Boot 3

- **결정**: Java 21 LTS (Long Term Support — 장기 지원 버전), Spring Boot 3.x 사용
- **배경**: Virtual Threads (JVM 차원의 가벼운 스레드, 동시성 비용을 크게 낮춤), Pattern Matching 등 신규 문법 데모. Spring Boot 3 는 Jakarta EE 9+ (구 javax → jakarta 패키지 이전 후 버전) / OTel 친화
- **대안**: Java 17 (LTS) — 안정적이지만 21 의 신규 문법 (Virtual Threads 등) 데모는 못함
- **결과**: Phase 2 에서 Virtual Threads on/off 비교 벤치 가능
- **점검 메모**: Spring Initializr 기본값이 Boot 4.x 로 올라간 시점에는 Phase 1 착수 전에 Boot 3 유지 vs Boot 4 전환을 다시 결정한다.

## ADR-002 — 서비스 간 통신: Kafka 비동기

- **결정**: REST 동기 호출 대신 Kafka 이벤트로 통신 (Phase 2부터)
- **배경**: 분산 트레이싱·SAGA·Outbox·Consumer Lag (consumer 가 못 따라잡고 있는 메시지 적체량) 모두 보여주기 위함
- **대안**: REST + Feign (Spring 의 선언형 HTTP 클라이언트) — 구현 단순하지만 데모 가치 약함
- **결과**: Trace 시각화의 메인 케이스로 활용

## ADR-003 — 서비스별 DB 분리

- **결정**: order/payment/inventory 각자의 PostgreSQL 스키마
- **배경**: 마이크로서비스 원칙 + 독립 진화 (한 서비스 스키마 변경이 다른 서비스 배포를 막지 않게). 분산 트랜잭션 (여러 DB 를 하나의 트랜잭션으로 묶는 것) 의 어려움을 SAGA 로 풀어내는 데모
- **대안**: 단일 DB + 스키마 분리 — 운영 단순하지만 학습 효과 약함
- **결과**: SAGA / Outbox 패턴 데모 가능

## ADR-004 — Tempo (vs Jaeger)

- **결정**: 분산 트레이싱 백엔드 (trace 데이터를 저장하고 검색해주는 시스템) 로 Tempo 채택
- **배경**: Grafana 스택 통합, S3 호환 스토리지 (저비용 객체 저장소를 그대로 쓸 수 있음) 로 운영 단순. OTel과 친화적
- **대안**: Jaeger — 성숙도 높지만 별도 UI/스토리지 운영 부담
- **결과**: Grafana 한 곳에서 메트릭/로그/트레이스 모두 조회

## ADR-005 — OpenTelemetry 표준

- **결정**: Micrometer + OTel Java Agent. 벤더 중립 (특정 회사 도구에 코드가 묶이지 않게)
- **배경**: 향후 백엔드 (Datadog/NewRelic/Pinpoint 같은 상용 APM — Application Performance Monitoring 도구) 교체 시 코드 변경 최소화
- **대안**: 벤더 SDK 직접 사용 — 한 번 쓰면 다른 도구로 갈아탈 때 코드 수정 큼 (vendor lock-in)
- **결과**: 옵저버빌리티 백엔드를 자유롭게 갈아끼울 수 있음

## ADR-006 — 실행 환경: Docker Compose 우선

- **결정**: Phase 1~3 은 Docker Compose, Phase 4 에서 Kind (로컬에서 K8s 클러스터를 컨테이너 안에 띄우는 도구) 옵션 추가
- **배경**: 진입 장벽 최소화. 처음 보는 사람이 5분 안에 띄워볼 수 있어야 함
- **대안**: 처음부터 Kubernetes — 학습/세팅 부담 큼
- **결과**: README Quick Start 한 줄 실행 가능

## ADR-007 — OTel: Spring Boot starter (Java agent 대신)

- **결정**: `opentelemetry-spring-boot-starter` 의존성으로 자동 계측 (코드 변경 없이 Spring 호출/HTTP/JDBC 등을 가로채 trace 생성). Java agent (`-javaagent` 로 JVM 시작 시 붙이는 자동계측 방식) 는 채택하지 않음.
- **배경**: 데모 환경 진입장벽 최소화 + Spring 통합 깊이. agent 가 운영에서의 표준이지만, 의존성으로 추가하는 starter 방식은 빌드/실행이 단순.
- **대안**: OTel Java agent — `bootRun` 에 `-javaagent=...` 부착 필요, 더 광범위한 자동계측, 코드 0줄 변경
- **결과**: Phase 4 에서 K8s 단계로 갈 때 agent 옵션을 추가 검토. 그때까지는 starter 로 충분.

## ADR-008 — OTel 메트릭 송신은 끄고 Prometheus 만 쓴다

- **결정**: `otel.metrics.exporter=none`, `otel.instrumentation.micrometer.enabled=false`
- **배경**: 메트릭은 Micrometer → `/actuator/prometheus` 경로가 이미 안정 가동 중. OTel 메트릭과 같이 내보내면 같은 메트릭이 두 이름으로 보여 대시보드 혼란.
- **대안**: 모든 신호를 OTel Collector 로 단일화 → 일관성은 좋지만 Micrometer 메트릭 이름 호환 깨질 위험
- **결과**: 트레이스/로그만 OTel 경로, 메트릭은 Prometheus 가 직접 긁어옴. Phase 2 Step 2 알람 룰 작성도 기존 메트릭 이름 그대로 사용 가능.

## ADR-009 — Outbox 패턴은 order-service 에만, payment/inventory 는 트랜잭션 커밋 직후 발행

- **결정**:
  - order-service: 도메인 변경 (Aggregate — DDD 의 일관성 단위, 하나의 트랜잭션으로 같이 바뀌는 객체 묶음) 과 *같은* 트랜잭션에서 `outbox_events` 행을 INSERT → 별도 폴러가 그 행을 읽어 Kafka 로 발행. DB 와 발행이 한 단위라 *발행 누락 0%*.
  - payment/inventory: 트랜잭션 커밋 *직후* (Spring 의 `TransactionSynchronization.afterCommit()` 훅 — 트랜잭션이 무사히 커밋된 다음 실행되는 콜백) 에 KafkaTemplate 으로 직접 발행. 커밋과 발행 사이의 짧은 윈도우 동안 프로세스가 죽으면 발행을 놓친다.
- **배경**: order 의 PAID/FAILED 는 비즈니스 시스템 (매출/배송 등) 의 *진실의 단일 원천* — 누락은 치명적. payment/inventory 의 이벤트는 *보조 신호* (메트릭/알림용) 로, 잠깐 누락돼도 도메인 일관성은 깨지지 않는다.
- **대안**: 모든 서비스에 outbox → 일관성은 더 좋지만 폴러·테이블·운영 부담이 3배. afterCommit 훅은 "DB 는 바뀌었는데 발행이 못 갔다" 가 가능 — 그 위험을 *어디서 받아들일지* 의 trade-off.
- **결과**: order 는 100% 보장, payment/inventory 는 best-effort (대부분 가지만 드물게 누락 가능). 추후 Kafka 기반 SAGA 로 흐름이 바뀌면 (Step 3b) 그때 payment/inventory 도 outbox 로 격상 검토.

## ADR-010 — Kafka 는 idempotent producer + at-least-once consumer

- **결정**:
  - **producer**: `enable.idempotence=true` (브로커 단에서 PID + 시퀀스로 중복을 거름 — 재시도로 같은 메시지가 두 번 들어가도 한 번만 저장), `acks=all` (모든 복제본이 받았을 때만 성공으로 응답).
  - **consumer 측 멱등성** (Step 3b 이후 도입): inventory 는 `(orderId, productId)` UNIQUE 제약, payment 는 orderId 를 키로 사용 — *도메인 자연 키* 로 같은 메시지의 두 번째 처리를 흡수.
- **배경**: Kafka transactions (consumer 가 한 메시지를 읽고, 처리하고, 다음 토픽에 쓰는 read-process-write 를 *하나의 원자 단위* 로 묶는 기능) 는 강력하지만 운영 복잡도가 크다 (transactional.id 관리, KIP-447 같은 격리 시맨틱 등). 같은 효과를 *도메인 자연 키 + UNIQUE 제약* 으로 얻을 수 있다면 그쪽이 단순.
- **대안**: Kafka transactions — 효과 강력, 단 운영 노하우 필요.
- **결과**: at-least-once (메시지가 최소 한 번은 도착, 가끔 중복). 같은 메시지가 두 번 와도 도메인이 동일 결과를 내도록 설계. Phase 4 카오스 시나리오에서 의도적 중복 메시지로 멱등성 검증 가능.

## ADR-011 — Inbox 패턴 + reconciliation 잡으로 부정합을 잡는다

- **결정**:
  1. order-service 가 `payment.events` / `inventory.events` 를 consume 해서 `payment_inbox` / `inventory_inbox` 에 멱등 (UNIQUE 키) 저장.
  2. 별도 스케줄 잡 (정기 실행되는 백그라운드 작업) 이 inbox 와 Order 상태를 비교해, 서로 다른 진실을 가진 *부정합* 을 카운터로 노출.
- **배경**: [2026-05-07 케이스 스터디](../case-studies/2026-05-07-payment-timeout-race.md) 에서 in-doubt 윈도우 (호출자는 timeout 으로 끊겼는데 피호출자는 작업을 끝내버려 결과를 알 수 없는 구간) 때문에 `Order=FAILED, Payment=SUCCESS` 부정합이 났다. 이건 동기 호출의 본질적 함정 — *발생 자체를 막기* 보다 *발생 사실을 모니터링* 하는 게 현실적.
- **대안 1 — 자동 보정**: 위험. 부정합인지, 일시적 경합 (race — 서로 다른 시점의 상태가 비교되는 일시 현상) 인지 즉시 구별이 어렵고, 잘못된 자동 보정은 새 사고를 만든다. 사람 개입용 *신호* 로 두는 편이 안전.
- **대안 2 — 받자마자 Order 에 반영 (Step 3c 가 갈 방향)**: 더 깊은 변화 (동기 흐름을 비동기로 전환). 우선 모니터링 신호부터 두고, 본격 비동기 전환은 후속.
- **결과**: `reconciliation.inconsistency{kind=order_failed_payment_succeeded}` 카운터 노출. 이 값이 > 0 이 되면 P1 알람으로 받아 즉시 인지. inbox 자체는 도메인 진실이 아니라 *외부 신호의 거울* (다른 서비스가 보낸 이벤트의 사본) 이라는 점을 코드 주석에도 명시.

## ADR-012 — slow-query-detector 는 DataSource 단에서 가로챈다

- **결정**: `modules/slow-query-detector` 는 `DataSource` bean 을 datasource-proxy 로 감싸는 BeanPostProcessor (Spring 이 만든 bean 을 후처리하는 훅) 형태로 구현. JPA/JDBC/MyBatis 어느 경로든 같은 자리에서 측정.
- **배경**: 슬로우 쿼리/N+1 을 잡을 수 있는 위치는 셋:
  1. Hibernate Statistics — Hibernate 가 자체 제공하는 통계. JPA *밖* 경로 (MyBatis, 순수 JDBC) 는 못 잡는다.
  2. Repository AOP — Spring Repository 메서드 호출을 가로채는 방식. 한 메서드 안의 여러 SQL 을 한 덩어리로만 보여줘 N+1 분석에 부족.
  3. DataSource 프록시 — 가장 낮은 계층. 어떤 경로로 들어와도 SQL 한 건 단위로 균일하게 보인다.

  3번이 가장 일관성 있어 채택.
- **대안**: p6spy — 같은 DataSource 단 라이브러리지만 `spy.properties` 파일 + JDBC URL 변경이 필요. datasource-proxy 는 Spring 과 자연스럽게 결합되고 JDBC URL 을 건드리지 않는다.
- **결과**: 사용자는 의존성만 추가하면 끝. DataSource 구현 (Hikari/Tomcat/etc.) 무관. v0.1 은 슬로우 + N+1 만 측정, v0.2 후보로 OTel span event attach (감지 결과를 trace span 에 이벤트로 첨부해 Tempo 화면에서 바로 보이게) 가 있음 (자세한 설계는 [modules/slow-query-detector/DESIGN.md](../modules/slow-query-detector/DESIGN.md)).

## ADR-016 — Adaptive concurrency limiter (Netflix concurrency-limits, Gradient2)

- **결정**: order-service 의 외부 호출 (`InventoryClient`, `PaymentClient`) 에 Netflix concurrency-limits 의 `Gradient2Limit` 기반 adaptive limiter 를 적용. RestClient `ClientHttpRequestInterceptor` 위치에 끼워 매 호출에 acquire/release. queueSize=0 — 한도 초과 시 즉시 `LimitExceededException` (503 + Retry-After 1s).
- **배경**:
  - 현재 client 에는 *고정 timeout* 만 있다. backend (payment/inventory) 가 느려지면 호출자 (order) 의 thread / connection 이 timeout 만큼 점유되고, 새 요청은 그 뒤에 줄을 선다. 이게 *cascade* — 한 곳의 지연이 호출자 → 호출자 → ... 도미노로 번짐. 사고 회고 [2026-05-07-payment-timeout-race](../case-studies/2026-05-07-payment-timeout-race.md) 의 in-doubt 윈도우와 같은 부류.
  - 대비책으로 *고정 동시 실행 한도* (Resilience4j Bulkhead) 도 있지만, *내가 정한 한도* 가 backend 의 *지금 처리 능력* 과 일치한다는 보장이 없다. backend 가 평소의 절반 능력으로 떨어졌을 때도 같은 N 명이 들어가면 cascade 가 그대로.
  - adaptive 는 *backend 의 latency 응답* 으로 한도를 자동 조절. RTT 가 길어지면 한도 즉시 축소 (multiplicative decrease) — backend 가 회복되면 latency 가 정상화되고 한도도 자동 증가. 사람이 손대지 않아도 됨.
  - 운영 표준 — Netflix / AWS / 카카오 / 라인 backend mesh 가 같은 부류 (Netflix concurrency-limits, AWS App Mesh adaptive concurrency 등).
- **대안**:
  1. **고정 Bulkhead (Resilience4j)** — 정적 한도. 위에 적은 한계 — backend 능력 변동에 대응 못 함.
  2. **circuit breaker 만** — backend 가 *완전 장애* 일 때만 차단. *부분 장애 (느려짐)* 에 약함.
  3. **자체 구현** — Gradient2 / Vegas / TCP-style 알고리즘은 디테일이 까다로움. 검증된 라이브러리 가져오는 편이 안전.
  4. **client-side rate limit (RPS)** — 고정값. 트래픽이 자연 변동하면 *충분한데도* 거절하거나 *못 따라가는데도* 그대로 보냄.
- **결과**:
  - **알고리즘** — Gradient2 (TCP Vegas 로부터 영감): long-term RTT 평균과 short-term RTT 평균의 비율 (gradient) 로 limit 조절. gradient ≈ 1 이면 limit 천천히 증가 (probe), gradient < 1 이면 limit 즉시 축소.
  - **acquire 위치** — RestClient interceptor (connect/read timeout 과 같은 layer). 호출 메서드 단위가 아니라 *upstream service 단위* 로 한도 격리 (`payment` vs `inventory` 분리).
  - **5xx → onDropped** — backend 가 망가지고 있다는 신호. limit 더 공격적으로 축소.
  - **4xx → onSuccess** — 비즈니스 결과 (404/409/402). backend 부담과 무관.
  - **queueSize=0** — 한도 초과 시 즉시 거절. 줄에 끼면 cascade 가 *호출자에서* 다시 발생.
  - **Retry-After: 1s** — RFC 7231. 짧으면 클라이언트가 같은 거절 만남, 길면 backend 회복 후 늦게 돌아옴. 1s 가 균형점.
  - **메트릭** — `client.concurrency.limit{upstream=payment|inventory}`, `client.concurrency.in_flight{...}` gauge.
  - **enabled=false 토글** — 도입 직후 운영 사고 시 기존 (timeout 만) 동작으로 즉시 롤백 가능.
  - **다음 phase 신호**: 한 호출 그래프에 limiter 가 여러 단 쌓이면 (order → payment → external PG) backpressure 가 *위로* 전파되어야 — Reactive (Project Reactor / RxJava) 의 backpressure 와 결합 검토. 본 phase 는 단일 layer.
  - runbook: [client-concurrency-limit-saturated](../docs/runbook/client-concurrency-limit-saturated.md).

## ADR-015 — JFR continuous profiling 을 항상 켜둔다

- **결정**: `modules/jfr-recorder-starter` 를 만들고, 의존성을 추가하면 부팅 시 JFR (Java Flight Recorder — JDK 표준 저오버헤드 프로파일러) 을 *상시* 켠다. `rollover` (기본 5분) 주기마다 chunk 를 디스크에 떨구고 `maxRetained` (기본 24개 = 2시간) 만큼 보존. 운영자는 `/actuator/jfr/{tag}` 로 즉시 ad-hoc dump trigger 가능.
- **배경**:
  - 사고 회고에서 가장 답답한 순간이 "p99 가 튀었는데 *그 시점 무슨 메서드가 CPU 를 먹고 있었는지* 알 수 없다" 는 것. 그때 가서 켜는 방식 (async-profiler 를 이상 발생 후 attach) 은 *놓친 사고 윈도우* 를 재현할 때까지 기다려야 함.
  - 운영 표준 (Datadog Continuous Profiler / NHN APM / 라인 LINE Profiler) 은 *상시 켠 채* chunk 단위로 보존. JFR 의 default 설정 오버헤드는 ~1% 라 상시 운영에 부담이 적다.
  - 메트릭/로그/trace 만으로는 *런타임 내부* (allocation 패턴, lock contention, JIT, GC) 가 보이지 않는다 — 옵저버빌리티의 4번째 신호 = profile.
- **대안**:
  1. **async-profiler agent (`-agentpath`) 상시 부착** — 더 정밀 (특히 wall-clock, allocation), 그러나 native 라이브러리 배포 부담. 컨테이너 이미지에 별도 layer.
  2. **알람 발화 시 사람이 attach** — 운영 부담 + 윈도우 놓침. 위에 적은 핵심 동기와 어긋남.
  3. **OTel profile signal (alpha)** — 표준화 진행 중이지만 도구 ecosystem (JMC / async-profiler view) 이 아직 file 기반. 시기상조.
- **결과**:
  - 상시 켠 채 5분마다 rollover, 2시간 분량 보존. 사고 발생 후 평균 30분 ~ 1시간 안에 분석을 시작한다는 SRE 경험치의 2배 버퍼.
  - PII 보호: `mask-sensitive-events=true` 로 `jdk.SocketRead/Write`, `jdk.FileRead/Write` 를 *발생 시점* 에 disable (post-hoc 마스킹과 다름 — 데이터가 아예 안 들어감).
  - actuator endpoint 는 exposure 미허용 시 bean 자체가 등록되지 않음 (`@ConditionalOnAvailableEndpoint`) — 권한 가드 1차 방어.
  - Java 21 toolchain 강제 — JFR 은 11+ 이지만 다른 모듈과 일관성.
  - JFR 자체가 비활성인 환경 (일부 GraalVM, 일부 컨테이너) 에선 `JfrRecorder.start()` 가 *예외를 throw 하지 않고* 메트릭 + WARN 로그로만 알림 → 사용자 앱 부팅이 깨지지 않음.
  - 운영 가이드: [docs/runbook/jfr-analysis.md](../docs/runbook/jfr-analysis.md) — JMC / async-profiler / programmatic 분석 흐름.
  - **다음 phase 신호**: 사고 → JFR 분석 흐름이 자리잡고 나면, (a) chunk S3 자동 업로드 + retention extend, (b) OTLP profile signal stable 화 시 그쪽 exporter 전환, (c) async-profiler agent 로 격상 (정밀도 더 필요할 때) — 이 셋이 후보.

## ADR-014 — Tail-based sampling (OTel Collector)

- **결정**: trace sampling 을 *head* (요청 시작 시 결정) 에서 *tail* (trace 완료 후 결정) 로 전환. OTel Collector 의 `tail_sampling` processor 에 composite policy — `errors → 100%`, `http 5xx → 100%`, `latency > 500ms → 100%`, `random → 1%` — 를 적용.
- **배경**:
  - head-based 1% 만 보면 전체 비용은 낮지만 *정작 보고 싶은 error / slow trace* 가 99% 손실된다. p99 디버깅 / 사고 회고가 우연 (sample 에 걸렸을 때만 가능) 에 의존.
  - 100% 를 보내면 cost / network / 백엔드 (Tempo) 부담이 ~100x. 데모 환경은 견디지만 운영은 곧 깨진다.
  - tail-based 는 *trace 의 결과* 를 보고 결정 — error / slow 는 100% 로 보존하면서 정상 trace 는 1% 만 들어와 전체 비용은 ~1.x% 에 머문다. 보존하고 싶은 신호의 *recall* 을 100% 로 끌어올린다.
- **대안**:
  1. **OTel SDK 측 ParentBased + TraceIdRatioBased(0.01)** — head-based. 간단/저비용, 표본 균일. error 보존이 안 됨 (위 문제 그대로).
  2. **상시 100% 송신 + 백엔드 (Tempo) 에서 query 시점에만 필터** — 보존은 완벽하지만 비용 그대로. 작은 팀에선 채택 어려움.
  3. **error 만 100% (latency 없이)** — error 는 잡히지만 *non-error 인데 느린* trace (deadlock, GC, lock contention) 가 누락됨.
  4. **3계층 (SDK head 1% + collector tail) 조합** — 데이터 손실이 두 단계로 누적. SDK 가 1% 만 보내면 collector 의 tail 결정도 1% 안에서만. 본질적으로 head 와 같음 — 채택 불가.
- **결과**:
  - SDK: 100% 송신 (현 OTel starter 기본).
  - Collector: `tail_sampling` 으로 OR 결합 — error/5xx/slow/random.
  - `decision_wait: 10s` — root span 시작 후 10s 동안 child span 이 도착할 시간을 준다. 우리 시스템 p99 < 5s 기준으로 안전.
  - `num_traces: 50000` — 동시 보존 trace 상한. 데모 환경은 충분, 운영은 트래픽 × decision_wait × 평균 span 수를 보고 sizing.
  - `memory_limiter` 를 파이프라인 *맨 앞* 에 둬서 OOM 으로 collector 가 죽는 사고를 방지. saturation 은 별도 알람 (`tail_sampling_buffer_saturation`) 으로 잡는다.
  - 운영 표준 (Datadog Continuous Profiler / NewRelic / Naver Pinpoint / 라인 LINE Trace) 이 거의 같은 결합 — error 보존 + latency 보존 + 잔여 random.
  - **다음 phase 신호**: 단일 collector 가 saturation 에 닿는 순간 = 2계층 (loadbalancing collector → tail_sampling pool) 으로 가야 할 때. tail_sampling 은 *동일 trace 의 모든 span 이 같은 collector* 로 라우팅돼야 동작하므로 trace_id 기반 routing 이 필수.
  - runbook: [tail-sampling-buffer-saturation](../docs/runbook/tail-sampling-buffer-saturation.md).

## ADR-017 — OTel Collector 2-tier (loadbalancing → tail_sampling pool)

- **결정**: 단일 collector 의 후속 단계로 *agent → backend pool* 의 2-tier 옵션을 둔다. agent 는 어플리케이션의 OTLP 진입점만 책임, `loadbalancing` exporter (`routing_key=traceID`) 로 backend pool 에 분배. 실제 tail_sampling 은 backend pool 에서. compose override (`docker-compose.collector-2tier.yml`) 로 2-tier 토글, 검증 스크립트 (`verify-2tier.sh`) 로 *같은 trace 의 모든 span 이 같은 backend 로 가는지* 회귀 테스트.
- **배경**:
  - tail_sampling 은 *한 trace 의 모든 span 이 같은 collector 인스턴스 메모리에 있어야* 의사결정이 정확하다 (error/5xx/latency policy 의 OR 결합 — 한 결과라도 누락되면 잘못된 drop). [ADR-014](#adr-014--tail-based-sampling-otel-collector) 가 단일 collector 전제로 도입됐고, "단일 collector saturation = 2-tier 로 가야 할 때" 를 후속 신호로 명시했다.
  - 단순 round-robin 으로 horizontal scale 하면 같은 trace 의 span 이 여러 인스턴스로 흩어진다 — 한쪽엔 root span, 다른 쪽엔 ERROR span. 양쪽 다 *자기 buffer 만 보고 결정* 해서 양쪽 다 drop 되거나, error 신호 없는 root 만 보존되는 어처구니없는 결과 (head-based 와 동급으로 망가짐).
  - `loadbalancing` exporter 의 `routing_key=traceID` 는 consistent hashing 으로 *같은 traceID = 같은 backend 인스턴스* 를 보장. backend pool 에 인스턴스가 추가/제거돼도 대다수 trace 의 라우팅이 유지 (rendezvous hashing 변형 — k8s 환경의 endpoint 변동에 강함).
  - 운영 표준: AWS Distro for OpenTelemetry 의 reference 아키텍처, Honeycomb 의 *refinery* (같은 모양의 다른 구현), Datadog 의 *load-balancing exporter* — 모두 같은 패턴.
- **대안**:
  1. **단일 collector 만 강화 (vertical scale)** — 한 인스턴스의 메모리/CPU 를 키우는 방식. 한계 분명: collector 한 대가 죽으면 *모든* trace 가 손실, 단일 인스턴스의 throughput 상한 (~100k span/s) 에 닿으면 더 늘릴 길이 없음.
  2. **앞단 LB + round-robin** — 가장 단순. 위에 적은 *trace 분산* 문제로 tail_sampling 이 깨짐. 명백히 부적격.
  3. **SDK 단의 head-based 고정** — 비용 낮지만 [ADR-014](#adr-014--tail-based-sampling-otel-collector) 가 거부한 이유 그대로 (error/slow 신호 손실).
  4. **이미 1-tier 로 충분, 2-tier 는 미실시** — 데모 환경엔 충분하지만 *후속 phase 의 길* 을 닫는 결정. 현재 단계에서는 *옵션* 으로만 둬, 부담 없이 토글 가능하게.
- **결과**:
  - **agent 의 책임 분리** — agent 는 trace/metric/log 수신 + trace 만 backend pool 에 분배. metric/log 는 본인이 직접 Prometheus/Loki 로 보내 hop 절감 (trace 처럼 *완전한 단위 의사결정* 이 필요 없음).
  - **backend 의 책임 단일화** — tail_sampling + Tempo export 만. config 가 단순, 새 backend 인스턴스 추가가 쉬움 (config 파일 동일).
  - **resolver 선택** — docker-compose 는 `static` (compose 가 만든 컨테이너 이름), k8s 는 `dns` (headless service 의 endpoint slice). 본 단계는 static 으로 충분, k8s phase 에서 dns 로 전환.
  - **검증 스크립트** — `verify-2tier.sh` 가 100 trace 를 흘려보내고 각 trace 의 모든 span 이 같은 `otel.backend.id` 를 가지는지 확인. routing_key 가 깨지는 회귀를 즉시 잡는 안전망. CI 에서 docker-compose-action 으로 자동 실행 가능.
  - **메모리 분산** — backend N 개로 늘리면 한 인스턴스가 들고 있어야 할 trace 수가 ~1/N (consistent hashing 분포 균일 가정). 단일 collector OOM 위험을 N 배 늦출 수 있는 정량적 이득.
  - **다음 phase 신호**: (a) backend pool 의 자동 scale (HPA + endpoint slice + dns resolver 자동 갱신) — k8s phase 에서. (b) tail_sampling 결과 자체의 metrics 를 backend instance 별로 분리해 *consistent hashing 분포가 진짜 균일한지* 측정. (c) `routing_key=service` 등 다른 라우팅 키 검토 — 도메인 레벨 격리가 필요한 multi-tenant 시나리오.

## ADR-018 — JFR chunk 의 원격 (S3/MinIO) 자동 업로드

- **결정**: `jfr-recorder-starter` 에 `JfrChunkUploader` 인터페이스 + `S3JfrChunkUploader` 구현을 추가. Recorder 의 rollover 시점에 비동기 업로드 task 를 큐에 넣고, 업로드 자체 실패가 다음 chunk 생성을 막지 않게 격리. `/actuator/jfr` 응답에 local + remote chunks 모두 노출. 기본 disable, 운영 환경에서만 enable.
- **배경**:
  - [ADR-015](#adr-015--jfr-continuous-profiling-을-항상-켜둔다) 의 *다음 phase 신호* 첫 번째 항목 — chunk 가 디스크에만 있으면 컨테이너가 죽거나 노드가 빠지면 같이 사라진다. 사고 직전 (process 가 죽기 직전) 의 chunk 가 *가장 가치 있는 데이터* 인데, 디스크 전용 보존은 그걸 가장 못 보존하는 구조다.
  - 운영 표준 (Datadog Continuous Profiler / NHN APM) 은 *각 인스턴스의 agent 가 백엔드로 즉시 업로드* — 우리는 자체 운영을 가정하므로 S3 호환 (AWS S3 / MinIO / R2 / Ceph) 이 가장 자연스러운 자리. 한 번 적재하면 lifecycle 정책 (Glacier 이관, 90일 만료 등) 으로 비용도 통제됨.
  - 업로드를 동기 (rollover 가 끝나기 전에 업로드 완료 대기) 로 두면 다음 chunk 의 시작이 늦어진다 — 데이터 *연속성* 이 깨지는 위험. 비동기 single-thread executor 가 적절 — chunk 가 5분에 1개라 직렬로도 충분, 동시 실행으로 NIC/메모리를 점유하지 않음.
- **대안**:
  1. **모든 chunk 업로드** (현 결정) vs **error window 의 chunk 만 업로드** — 후자는 비용 낮지만 *어떤 chunk 가 error window 의 것인지* 를 *나중에* 알 수 없는 게 본질적 모순 (사고는 *예측 불가*). chunk 일단 다 올리고 retention 으로 비용 통제하는 편이 안전.
  2. **OTel profile signal (alpha) 로 직접 export** — 표준 진행 중이지만 도구 ecosystem (JMC / async-profiler) 이 아직 file 기반. 시기상조.
  3. **agent 사이드카로 분리** — k8s 환경 표준이지만 docker-compose 데모/단일 인스턴스에선 운영 부담 큼. 본 모듈은 *프로세스 안에서 관리* 가 더 단순.
  4. **retry queue 로 실패 chunk 재업로드** — 검토했으나 미채택. 실패 추적용 별도 디스크 큐가 디스크를 차게 만드는 위험이 있고, 디스크의 retention 보유분이 살아있어 *다음 사이클 직접 호출* 으로도 충분 (운영자 수동). 단순 idempotent 가 안전.
- **결과**:
  - **key 구조** — `{prefix}/{podId}/{yyyy/MM/dd}/HHmmss-filename.jfr`. 시간 prefix 로 lifecycle 정책 적용이 쉽고, podId 로 같은 deployment 의 인스턴스 충돌 차단.
  - **uploader 추상화** — `JfrChunkUploader` 인터페이스 + `Noop` 폴백. AWS SDK 가 classpath 에 없으면 자동 noop (의존성 부재로 사용자 앱이 깨지지 않게). 사용자가 직접 `JfrChunkUploader` bean 을 정의하면 자동 등록을 건너뜀 (GCS / Azure Blob 등 커스텀 backend).
  - **PII 보호** — [ADR-015](#adr-015--jfr-continuous-profiling-을-항상-켜둔다) 의 `mask-sensitive-events` 가 *발생 시점* 에 `jdk.SocketRead/Write` / `jdk.FileRead/Write` 를 거른다. chunk 자체에 PII 가 안 들어가는 상태로 업로드 — 그 후의 권한 모델은 S3 의 bucket policy / IAM 으로.
  - **자격증명 정책** — `accessKey/secretKey` 는 dev/local 전용. 운영은 IAM role + `DefaultCredentialsProvider` chain (정적 키가 비어 있을 때 자동 fallback). 정적 키를 운영에 두면 안 되는 가드.
  - **메트릭** — `jfr.upload.events{backend, outcome=ok|error|list_error}` 카운터 + `jfr.upload.duration` timer. P2 알람으로 *업로드 실패율* 모니터링 가능.
  - **ad-hoc dump 의 업로드** — 별도 토글 (`upload-ad-hoc-dumps`). 기본 disable — 운영자가 의도적으로 만든 단발성 산출물은 즉시 분석에 쓰는 게 보통이라 굳이 원격 보존 불필요.
  - **다음 phase 신호**: (a) chunk metadata 에 `incident_id` 를 추가해 alertmanager 가 발화한 사고와 chunk 를 결합 (사고 → S3 query). (b) 업로드 실패 retry queue (디스크 백업 큐 + exponential backoff). (c) S3 bucket policy 자동화 (lifecycle / KMS 암호화 / public-access-block). (d) MinIO 컨테이너를 데모 compose 에 옵션으로 추가 (현재는 dev 기본 disable).

## ADR-019 — OrderSAGA 를 Spring StateMachine 으로 모델링 (shadow → enforce 단계 도입)

- **결정**: 기존 `OrderService` 의 if/else SAGA 흐름을 *그대로 유지* 한 채, `OrderSagaConfig` (Spring StateMachine 의 `EnumStateMachineConfigurerAdapter`) 로 같은 흐름의 *명시 모델* 을 만든다. `OrderSagaCoordinator` 가 OrderService 와 *병행* (shadow) 으로 머신을 진행시키며, 결정 일관성을 `order.saga.consistency{result=ok|mismatch}` 카운터로 비교. enforce 모드 토글 (`app.saga.machine.enforce=true`) 로 mismatch 가 즉시 예외가 되어 CI/staging 에서 모델 회귀를 잡는다. 본 phase 는 in-memory persistence — 진실의 원천은 여전히 OrderService.
- **배경**:
  - SAGA 의 분기가 5개 (정상 / OUT_OF_STOCK / INVENTORY_INFRA / PAYMENT_DECLINED / PAYMENT_INFRA / UPSTREAM_LIMITED) 로 늘어나면서 보상 (compensation) 의 *어디서 어디로* 가 if/else 코드 곳곳에 흩어진다. 새 분기 추가 (예: 부분 결제, partial refund) 가 어려워지는 신호.
  - StateMachine 으로 옮기면: (a) 상태 전이가 *데이터* (config) 로 표현 → 그래프 시각화 가능 (Spring StateMachine 의 graph viewer 활용), (b) 가드/액션이 *명시 컴포넌트* — 단위 테스트가 쉬움, (c) 새 분기가 *config 한 곳* 의 추가로 끝남.
  - 동시에 *진실의 원천* 을 갑자기 모델로 옮기면 위험 — 기존 outbox / inbox / 멱등성 로직과 상호작용이 검증 안 됨. *shadow* 로 시작해 메트릭으로 검증한 뒤 격상하는 점진 도입이 안전.
- **대안**:
  1. **StateMachine 도입 안 함, if/else 유지** — 분기 5개 단계는 견딜만하지만, 6개~8개로 가면 코드 흐름이 뒤엉킴. 후속의 *부분 결제 / 부분 환불 / 환불 보상* 시나리오를 위해서는 미리 모델링.
  2. **자체 enum 기반 작은 state machine** — Spring StateMachine 의존성을 안 들여도 됨. 그러나 persistence (StateMachinePersister), 가드/액션 분리, listener / pre-/post-action interceptor 같은 *주변 인프라* 를 다시 만들어야 함. 운영 부담.
  3. **즉시 진실의 원천을 StateMachine 으로** — 기존 outbox / 멱등성 로직 손대야 하고 회귀 위험 큼. shadow 단계 없이 가는 건 비싼 사고 가능성.
  4. **choreography (각 서비스가 이벤트만 듣고 자기 일을 하는 구조) 로 전환** — Phase 2 Step 3c 의 진짜 목표. 그러나 그건 *동기 흐름 자체를 비동기로* 바꾸는 것이고, 본 결정은 *현재 흐름의 모델화*. 두 결정이 직교 (orthogonal) — 모델이 잘 잡혀 있으면 choreography 전환의 전후 비교도 같은 모델로 검증 가능.
- **결과**:
  - **상태 enum 분리** — `OrderSagaStates` (DRAFT / INVENTORY_RESERVING / INVENTORY_RESERVED / PAYMENT_CHARGING / COMPENSATING / PAID / FAILED) 가 도메인 enum (`OrderStatus`) 보다 *세분화*. 도메인 enum 은 외부 노출용 (마이그레이션 비용 큼) 이라 그대로 두고, SAGA 진행 표현은 별도 enum.
  - **이벤트 분리** — INVENTORY_OUT_OF_STOCK (비즈니스 실패) vs INVENTORY_INFRA_ERROR (인프라 장애) 를 분리. 보상 정책 (전자는 보상 불필요, 후자는 부분 잡힘 가능 → 보상 필요) 이 모델 레벨에서 다름. UPSTREAM_LIMITED 도 별도 이벤트로 — adaptive limiter 가 호출 자체를 거절한 케이스 (잡힌 게 없을 수도 있음).
  - **shadow 모드 (기본)** — `OrderSagaCoordinator.assertConsistent` 가 mismatch 시 메트릭만 올리고 throw 안 함. 모델 자체 버그가 운영 트래픽에 영향 없게.
  - **enforce 모드 (옵션)** — `app.saga.machine.enforce=true` 시 mismatch 가 즉시 IllegalStateException. CI/staging 에서 켜 회귀 즉시 인지.
  - **메트릭** — `order.saga.transitions{tag}` (전이 카운터), `order.saga.compensations{reason}` (보상 카운터), `order.saga.outcomes{outcome}` (종결 카운터), `order.saga.consistency{result}` (일관성), `order.saga.unhandled{state, event}` (정의되지 않은 transition).
  - **persistence 미적용** — 현재 in-memory. 진실의 원천이 OrderService 라 process 재시작 시 SAGA 인스턴스가 사라져도 도메인 정합은 영향 없음. enforce 격상 후에는 `StateMachinePersister` + Postgres 로 전환 필요.
  - **테스트 14개** — 모델 자체 9개 (5 시나리오 + terminal 보호 + unhandled), coordinator 5개 (shadow / enforce / unhandled / consistency).
  - **다음 phase 신호**: (a) `StateMachinePersister` + Postgres 로 SAGA 인스턴스 영속화 (재시작 후 복구), (b) 진실의 원천을 OrderService → StateMachine 으로 전환 (enforce 안정화 후), (c) graph viewer 자동화 (CI 에서 SVG 생성해 PR 첨부 — 모델 변경의 시각적 review), (d) Phase 2 Step 3c (choreography) 의 비동기 전환을 같은 모델로 검증.

## ADR-013 — 로그에 식별자 노출 정책 (PII 마스킹)

- **결정**:
  - `userId` 처럼 **한 사람을 1:1 로 식별하는 자연 키** 는 로그 평문 금지. `LogIds.userId(...)` 로 SHA-256 앞 4byte (8 hex) 해시에 `u:` prefix 를 붙여 찍는다 (예: `u:3a4f7b9c`). 같은 user 는 항상 같은 값 → trace 추적은 가능하지만 원본 ID 는 안 보인다.
  - `orderId` / `paymentId` / `reservationId` / `productId` 같은 **시스템 내부 surrogate 키** (DB 자동증가 ID, 사용자 식별과 무관) 는 평문 허용. 운영 grep 의 1차 식별자라 마스킹하면 디버깅 비용이 너무 커진다.
  - logback 패턴에 항상 `[%X{trace_id:-}/%X{span_id:-}]` 슬롯 유지 (OTel logback-appender 가 MDC 에 자동 주입).
  - logback 패턴에 `userId` MDC 슬롯도 함께 두되, *거기 들어가는 값은 반드시 `LogIds.userId(...)` 결과* (평문 userId 를 MDC 에 직접 넣으면 안 됨).
- **배경**: Phase 2~3 진행 중 `userId` 가 INFO 로그에 평문 노출되는 패턴이 점진적으로 늘어날 위험이 있어, *발생 전에* 정책과 헬퍼를 둔다. orderId 같은 surrogate 까지 마스킹하면 운영 디버깅이 사실상 불가능해지므로 균형을 잡았다.
- **대안 1 — 모든 ID 마스킹**: 보안은 강해지지만 운영성 손실이 크다.
- **대안 2 — 정책만 있고 헬퍼는 없음**: 사람마다 다른 방식으로 마스킹하면 같은 사용자가 다른 해시로 찍혀 trace 가 끊어진다.
- **결과**:
  - `services/{order,payment}-service/src/main/java/.../util/LogIds.java` 에 헬퍼.
  - inventory-service 는 `userId` 를 직접 다루지 않으므로 헬퍼 불필요.
  - logback 패턴에 `[%X{userId:-}]` 슬롯 추가 (order/payment).
  - 후속 (correlation-mdc-starter 정식 도입 시) 에 `Filter` 가 X-User-Id 헤더를 받아 자동으로 마스킹된 값을 MDC 에 넣도록 통합 예정.
