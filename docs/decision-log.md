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
