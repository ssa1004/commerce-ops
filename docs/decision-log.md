# Decision Log

설계 결정의 *이유*를 짧게 기록합니다 (Lightweight ADR).
포맷: 결정 / 배경 / 대안 / 결과.

---

## ADR-001 — Java 21 + Spring Boot 3

- **결정**: Java 21 LTS, Spring Boot 3.x 사용
- **배경**: Virtual Threads, Pattern Matching 등 신규 문법 데모. Spring Boot 3는 Jakarta EE 9+ / OTel 친화
- **대안**: Java 17 (LTS) — 안정적이지만 신규 어필 약함
- **결과**: Phase 2에서 Virtual Threads on/off 비교 벤치 가능
- **점검 메모**: Spring Initializr 기본값이 Boot 4.x로 올라간 시점에는 Phase 1 착수 전에 Boot 3 유지 vs Boot 4 전환을 다시 결정한다.

## ADR-002 — 서비스 간 통신: Kafka 비동기

- **결정**: REST 동기 호출 대신 Kafka 이벤트로 통신 (Phase 2부터)
- **배경**: 분산 트레이싱·SAGA·Outbox·Consumer Lag 모두 보여주기 위함
- **대안**: REST + Feign — 구현 단순하지만 데모 가치 약함
- **결과**: Trace 시각화의 메인 케이스로 활용

## ADR-003 — 서비스별 DB 분리

- **결정**: order/payment/inventory 각자의 PostgreSQL 스키마
- **배경**: 마이크로서비스 원칙 + 독립 진화. 분산 트랜잭션의 어려움을 SAGA로 풀어내는 데모
- **대안**: 단일 DB + 스키마 분리 — 운영 단순하지만 학습 효과 약함
- **결과**: SAGA / Outbox 패턴 데모 가능

## ADR-004 — Tempo (vs Jaeger)

- **결정**: 분산 트레이싱 백엔드로 Tempo 채택
- **배경**: Grafana 스택 통합, S3 호환 스토리지로 운영 단순. OTel과 친화적
- **대안**: Jaeger — 성숙도 높지만 별도 UI/스토리지 운영 부담
- **결과**: Grafana 한 곳에서 메트릭/로그/트레이스 모두 조회

## ADR-005 — OpenTelemetry 표준

- **결정**: Micrometer + OTel Java Agent. 벤더 중립
- **배경**: 향후 백엔드 (Datadog/NewRelic/Pinpoint) 교체 시 코드 변경 최소화
- **대안**: 벤더 SDK 직접 사용 — 락인
- **결과**: 옵저버빌리티 백엔드를 자유롭게 갈아끼울 수 있음

## ADR-006 — 실행 환경: Docker Compose 우선

- **결정**: Phase 1~3은 Docker Compose, Phase 4에서 Kind 옵션 추가
- **배경**: 진입 장벽 최소화. recruiter가 5분 안에 띄워볼 수 있어야 함
- **대안**: 처음부터 Kubernetes — 학습/세팅 부담 큼
- **결과**: README Quick Start 한 줄 실행 가능

## ADR-007 — OTel: Spring Boot starter (Java agent 대신)

- **결정**: `opentelemetry-spring-boot-starter` 의존성으로 자동 계측. Java agent (`-javaagent`)는 채택하지 않음.
- **배경**: 데모 환경 진입장벽 최소화 + Spring 통합 깊이. agent는 운영에서의 표준이지만, 의존성-기반은 빌드/실행이 단순.
- **대안**: OTel Java agent — `bootRun`에 `-javaagent=...` 부착 필요, 더 광범위한 자동계측, 코드 0줄 변경
- **결과**: Phase 4에서 K8s 단계로 갈 때 agent 옵션을 추가 검토. 그때까지는 starter로 충분.

## ADR-008 — OTel 메트릭 export는 끄고 Prometheus만 쓴다

- **결정**: `otel.metrics.exporter=none`, `otel.instrumentation.micrometer.enabled=false`
- **배경**: 메트릭은 Micrometer → `/actuator/prometheus` 경로가 이미 안정 가동 중. OTel 메트릭과 이중 노출하면 같은 메트릭이 두 이름으로 보여 대시보드 혼란.
- **대안**: 모든 신호를 OTel collector로 단일화 → 일관성↑ 이지만 Micrometer 메트릭 이름 호환 깨짐 위험
- **결과**: traces/logs만 OTel 경로, metrics는 Prometheus scrape. Phase 2 Step 2 알람 룰 작성도 기존 메트릭 이름 그대로 사용 가능.

## ADR-009 — Outbox 패턴은 order-service에만, payment/inventory는 afterCommit publish

- **결정**: order-service는 Aggregate 변경과 같은 트랜잭션에서 `outbox_events` 행 기록 → 별도 폴러가 Kafka 발행. payment/inventory는 `TransactionSynchronization.afterCommit()` 훅으로 발행 (이벤트 publisher가 트랜잭션 커밋 직후 호출됨).
- **배경**: order의 PAID/FAILED는 비즈니스 시스템(매출/배송 등) 진실의 단일 원천이라 publish 누락이 치명적. payment/inventory의 이벤트는 보조 신호(메트릭/알림용)로, 잠깐 누락돼도 도메인 일관성을 깨지 않음.
- **대안**: 모든 서비스에 outbox → 일관성↑, 하지만 폴러·테이블·운영 부담 3배. afterCommit 훅은 "DB는 바뀌었는데 publish 못 갔다"가 가능 — 그 위험을 어디서 받아들일지의 trade-off.
- **결과**: order는 100% 보장, payment/inventory는 best-effort. 추후 Kafka 기반 SAGA로 흐름이 바뀌면 (Step 3b) 그때 payment/inventory도 outbox로 격상 검토.

## ADR-010 — Kafka는 idempotent producer + at-least-once consumer

- **결정**: producer는 `enable.idempotence=true`, `acks=all`. consumer 측 (Step 3b 이후 도입) 멱등성은 inventory의 `(orderId, productId)` UNIQUE 제약 + payment의 orderId 키로 흡수.
- **배경**: 트랜잭션 producer/consumer + transactional commit은 운영 복잡도가 큼. 같은 효과를 도메인 멱등 키로 얻을 수 있다면 그쪽이 단순.
- **대안**: Kafka transactions (read-process-write 원자성) — 효과 강력, 하지만 KIP-447 etc. 운영 노하우 필요.
- **결과**: 같은 메시지가 두 번 와도 도메인이 동일 결과를 내도록 설계. Phase 4 카오스 시나리오에서 의도적 중복 메시지로 멱등성 검증 가능.

## ADR-011 — Inbox 패턴 + reconciliation 잡으로 부정합을 잡는다

- **결정**: order-service가 `payment.events` / `inventory.events`를 consume해서 `payment_inbox` / `inventory_inbox`에 멱등(UNIQUE 키) 저장. 별도 스케줄 잡이 inbox와 Order 상태를 비교해 *부정합*을 카운터로 노출.
- **배경**: [2026-05-07 케이스 스터디](../case-studies/2026-05-07-payment-timeout-race.md)에서 timeout in-doubt 윈도우 때문에 `Order=FAILED, Payment=SUCCESS` 부정합이 났다. 동기 호출의 본질적 함정이라 *발생 자체를 막기*보다 *발생 사실을 모니터링*하는 게 현실적.
- **대안 1 — 자동 보정**: 위험하다. 부정합이 진짜인지 일시적 race인지 즉답 어렵다. 사람 개입을 위한 신호로 두는 편이 안전.
- **대안 2 — 받자마자 Order에 반영 (Step 3c가 갈 방향)**: 더 깊은 변화. 우선 모니터링 신호부터 두고 본격 async 전환은 후속.
- **결과**: `reconciliation.inconsistency{kind=order_failed_payment_succeeded}` 카운터 노출. 이 값 > 0일 때 알람을 P1으로 받기만 하면 즉각 알게 된다. inbox 자체는 도메인 진실이 아니라 *외부 신호의 거울*이라는 점을 코드 주석에서도 명시.

## ADR-012 — slow-query-detector는 DataSource 단에서 가로챈다

- **결정**: `modules/slow-query-detector`는 `DataSource` bean을 datasource-proxy로 감싸는 BeanPostProcessor 형태로 구현. JPA/JDBC/MyBatis 어느 경로든 같은 자리에서 측정.
- **배경**: 슬로우 쿼리/N+1을 잡는 위치는 (a) Hibernate Statistics, (b) Repository AOP, (c) DataSource 프록시 셋 중 하나. Hibernate 한정은 너무 좁고, AOP는 한 메서드 안의 여러 SQL을 한 덩어리로만 봄. DataSource는 가장 낮은 층이라 모든 호출 경로를 균일하게 본다.
- **대안**: p6spy — 둘 다 DataSource 단인데 p6spy는 `spy.properties` + JDBC URL 변경이 필요. datasource-proxy는 Spring과 자연스럽게 결합되고 JDBC URL을 안 건드림.
- **결과**: 사용자는 의존성만 추가하면 끝. DataSource bean 종류(Hikari/Tomcat/etc.) 무관. v0.1은 슬로우 + N+1만, v0.2 후보로 OTel span event attach가 있음 (자세한 설계는 [modules/slow-query-detector/DESIGN.md](../modules/slow-query-detector/DESIGN.md)).
