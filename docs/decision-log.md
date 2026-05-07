# Decision Log

설계 결정의 *이유*를 짧게 기록합니다 (가벼운 ADR — Architecture Decision Record).
포맷: 결정 / 배경 / 대안 / 결과.

---

## ADR-001 — Java 21 + Spring Boot 3

- **결정**: Java 21 LTS (Long Term Support — 장기 지원 버전), Spring Boot 3.x 사용
- **배경**: Virtual Threads (JVM 차원의 가벼운 스레드, 동시성 비용을 크게 낮춤), Pattern Matching 등 신규 문법 데모. Spring Boot 3 는 Jakarta EE 9+ (구 javax → jakarta 패키지 이전 후 버전) / OTel 친화
- **대안**: Java 17 (LTS) — 안정적이지만 신규 어필 약함
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

- **결정**: order-service 는 도메인 변경 (Aggregate — DDD 의 일관성 단위, 하나의 트랜잭션으로 같이 바뀌는 객체 묶음) 과 같은 트랜잭션에서 `outbox_events` 행 기록 → 별도 폴러가 Kafka 발행. payment/inventory 는 `TransactionSynchronization.afterCommit()` 훅 (Spring 이 트랜잭션 커밋 직후 실행해주는 콜백) 으로 발행.
- **배경**: order 의 PAID/FAILED 는 비즈니스 시스템(매출/배송 등) 진실의 단일 원천이라 발행 누락이 치명적. payment/inventory 의 이벤트는 보조 신호(메트릭/알림용)로, 잠깐 누락돼도 도메인 일관성을 깨지 않음.
- **대안**: 모든 서비스에 outbox → 일관성은 더 좋지만, 폴러·테이블·운영 부담 3배. afterCommit 훅은 "DB 는 바뀌었는데 발행을 못 갔다" 가 가능 — 그 위험을 어디서 받아들일지의 trade-off.
- **결과**: order 는 100% 보장, payment/inventory 는 best-effort (대부분 가지만 드물게 누락 가능). 추후 Kafka 기반 SAGA 로 흐름이 바뀌면 (Step 3b) 그때 payment/inventory 도 outbox 로 격상 검토.

## ADR-010 — Kafka 는 idempotent producer + at-least-once consumer

- **결정**: producer 는 `enable.idempotence=true` (재시도로 같은 메시지가 두 번 들어가도 한 번만 저장), `acks=all` (모든 복제본이 받았을 때만 성공). consumer 측 (Step 3b 이후 도입) 멱등성은 inventory 의 `(orderId, productId)` UNIQUE 제약 + payment 의 orderId 키로 흡수.
- **배경**: Kafka transactions (read-process-write 를 원자적으로 묶는 기능) + 트랜잭션 커밋은 운영 복잡도가 큼. 같은 효과를 도메인 멱등 키 (UNIQUE 제약 같은 자연 키) 로 얻을 수 있다면 그쪽이 단순.
- **대안**: Kafka transactions — 효과 강력, 하지만 KIP-447 등 운영 노하우 필요.
- **결과**: at-least-once (메시지가 최소 한 번은 도착, 가끔 중복). 같은 메시지가 두 번 와도 도메인이 동일 결과를 내도록 설계. Phase 4 카오스 시나리오에서 의도적 중복 메시지로 멱등성 검증 가능.

## ADR-011 — Inbox 패턴 + reconciliation 잡으로 부정합을 잡는다

- **결정**: order-service 가 `payment.events` / `inventory.events` 를 consume 해서 `payment_inbox` / `inventory_inbox` 에 멱등 (UNIQUE 키) 저장. 별도 스케줄 잡 (정기 실행되는 백그라운드 작업) 이 inbox 와 Order 상태를 비교해 *부정합* (서로 다른 진실을 가진 상태) 을 카운터로 노출.
- **배경**: [2026-05-07 케이스 스터디](../case-studies/2026-05-07-payment-timeout-race.md) 에서 timeout in-doubt 윈도우 (호출자는 끊겼는데 피호출자는 작업을 끝내버려 결과를 알 수 없는 구간) 때문에 `Order=FAILED, Payment=SUCCESS` 부정합이 났다. 동기 호출의 본질적 함정이라 *발생 자체를 막기* 보다 *발생 사실을 모니터링* 하는 게 현실적.
- **대안 1 — 자동 보정**: 위험하다. 부정합이 진짜인지 일시적 경합 (race — 서로 다른 시점의 상태가 비교되는 일시 현상) 인지 즉답 어렵다. 사람 개입을 위한 신호로 두는 편이 안전.
- **대안 2 — 받자마자 Order 에 반영 (Step 3c 가 갈 방향)**: 더 깊은 변화. 우선 모니터링 신호부터 두고 본격 비동기 전환은 후속.
- **결과**: `reconciliation.inconsistency{kind=order_failed_payment_succeeded}` 카운터 노출. 이 값 > 0 일 때 알람을 P1 으로 받기만 하면 즉각 알게 된다. inbox 자체는 도메인 진실이 아니라 *외부 신호의 거울* (다른 서비스가 보낸 이벤트의 사본일 뿐) 이라는 점을 코드 주석에서도 명시.

## ADR-012 — slow-query-detector 는 DataSource 단에서 가로챈다

- **결정**: `modules/slow-query-detector` 는 `DataSource` bean 을 datasource-proxy 로 감싸는 BeanPostProcessor (Spring 이 만든 bean 을 후처리하는 훅) 형태로 구현. JPA/JDBC/MyBatis 어느 경로든 같은 자리에서 측정.
- **배경**: 슬로우 쿼리/N+1 을 잡는 위치는 (a) Hibernate Statistics (Hibernate 가 자체 제공하는 통계), (b) Repository AOP (Spring 의 Repository 메서드를 가로채는 방법), (c) DataSource 프록시 셋 중 하나. Hibernate 한정은 JPA 외 경로 (MyBatis/순수 JDBC) 를 못 잡고, AOP 는 한 메서드 안의 여러 SQL 을 한 덩어리로만 봄. DataSource 는 가장 낮은 층이라 모든 호출 경로를 균일하게 본다.
- **대안**: p6spy — 둘 다 DataSource 단인데 p6spy 는 `spy.properties` 파일 + JDBC URL 변경이 필요. datasource-proxy 는 Spring 과 자연스럽게 결합되고 JDBC URL 을 안 건드림.
- **결과**: 사용자는 의존성만 추가하면 끝. DataSource bean 종류(Hikari/Tomcat/etc.) 무관. v0.1 은 슬로우 + N+1 만, v0.2 후보로 OTel span event attach (감지 결과를 trace span 에 첨부해 trace 화면에서 바로 보이게) 가 있음 (자세한 설계는 [modules/slow-query-detector/DESIGN.md](../modules/slow-query-detector/DESIGN.md)).
