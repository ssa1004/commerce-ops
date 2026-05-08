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
