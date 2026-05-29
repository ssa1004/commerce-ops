# 백엔드 스킬 인덱스 — 이 레포에서 무엇을 배우나

> 이 레포가 시연하는 백엔드 / 운영 패턴을 **"무엇 → 이 레포 어디서 → 왜(ADR) → 더 깊은 이론"** 으로 잇는 학습용 인덱스.
> "이 패턴 공부하려면 어디부터 보나"의 진입점. 설명을 다시 쓰지 않고 코드·결정·이론으로 연결만 한다.

## 메시징 · 일관성

| 패턴 | 이 레포 어디서 | 왜 (ADR) | 한 줄 |
|------|---------------|---------|-------|
| **Outbox 패턴** | `services/order-service` 의 outbox 테이블 + poller | [ADR-009](decision-log.md) | DB 커밋과 이벤트 발행을 한 트랜잭션으로 — dual-write 문제 해소 |
| **`SELECT … FOR UPDATE SKIP LOCKED`** | outbox poller | ADR-009 | 여러 poller 인스턴스가 같은 행을 겹쳐 안 가져감 |
| **Inbox + reconciliation** | consumer 측 dedup + 정합성 잡 | [ADR-011](decision-log.md) | at-least-once 의 중복을 멱등 처리 + 주기적 부정합 검출 |
| **idempotent producer / at-least-once consumer** | Kafka 설정 | [ADR-010](decision-log.md) | 전달 의미의 현실적 선택 |
| **consumer rebalance + commit 전략** | Kafka consumer | [ADR-021](decision-log.md) | rebalance 중 중복/유실 최소화 |

→ 이론: `dev-lab/kafka`, `dev-lab/cdc` (Outbox vs CDC), `dev-lab/distributed-systems` (exactly-once 환상)

## 분산 트랜잭션

| 패턴 | 이 레포 어디서 | 왜 | 한 줄 |
|------|---------------|-----|-------|
| **SAGA (orchestration)** | OrderSAGA (Spring StateMachine) | [ADR-019](decision-log.md) | 여러 서비스 흐름 + 실패 시 보상. shadow → enforce 단계 도입 |
| **보상 트랜잭션** | payment 실패 → inventory 복구 | ADR-019 | 강한 일관성 대신 최종 일관성 + 보상 |

→ 이론: `dev-lab/temporal` (durable execution 대안), `dev-lab/distributed-systems` (2PC vs Saga)

## 회복탄력성 (Resilience)

| 패턴 | 이 레포 어디서 | 왜 | 한 줄 |
|------|---------------|-----|-------|
| **HTTP retry + 지수 백오프 + jitter** | 서비스 간 REST client | [ADR-022](decision-log.md) | thundering herd 없이 일시 장애 흡수 |
| **Adaptive concurrency limiter** | Gradient2 (Netflix concurrency-limits) | [ADR-016](decision-log.md) | 고정 풀 대신 latency 기반 동적 한도 |
| **HikariCP 튜닝 + leak detection** | DataSource | [ADR-020](decision-log.md) | 커넥션 풀 포화 / 누수 조기 검출 |

→ 이론: `dev-lab/resilience` (circuit breaker / bulkhead), `dev-lab/networking` (커넥션 풀 sizing)

## 관측성 (Observability)

| 패턴 | 이 레포 어디서 | 왜 | 한 줄 |
|------|---------------|-----|-------|
| **OpenTelemetry (Spring starter)** | 전 서비스 자동 계측 | [ADR-005](decision-log.md), [ADR-007](decision-log.md) | vendor 중립 + java agent 대신 starter |
| **trace ↔ log correlation** | `correlation-mdc-starter` (trace_id → MDC) | [ADR-025](decision-log.md) | 한 trace_id 로 metric→trace→log 점프 |
| **Tail-based sampling** | OTel Collector 2-tier | [ADR-014](decision-log.md), [ADR-017](decision-log.md) | 느린/실패 trace 우선 보존 |
| **SLO + error budget + 런북** | [docs/slo.md](slo.md), [docs/runbook/](runbook/) | — | 알람마다 "어디부터 보나" 절차서 |
| **JFR continuous profiling** | `jfr-recorder-starter` | [ADR-015](decision-log.md), [ADR-024](decision-log.md) | 상시 저오버헤드 프로파일 + S3 업로드 |
| **slow query / N+1 자동 감지** | `slow-query-detector` (DataSource 가로채기) | [ADR-012](decision-log.md) | 운영 중 느린 쿼리 / N+1 카운터화 |

→ 이론: `dev-lab/observability` (3축 + SLI/SLO), `dev-lab/jvm` (JFR), `dev-lab/performance` (USE/RED), `dev-lab/incident-response` (런북)

## Spring Boot 심화

| 패턴 | 이 레포 어디서 | 한 줄 |
|------|---------------|-------|
| **AutoConfiguration starter 제작** | `modules/*-starter` | 의존성만 추가하면 자동 활성 — 4 종 자체 starter |
| **Spring StateMachine** | OrderSAGA | 상태 전이를 선언적으로 |
| **Spring Batch** | (billing-platform 에서 더 깊이) | — |

→ 이론: `dev-lab/system-design` (헥사고날 / 모듈 경계)

## 운영 / SRE

| 패턴 | 이 레포 어디서 | 한 줄 |
|------|---------------|-------|
| **Chaos engineering** | `chaos/`, `modules/chaos-injector` | 의도적 장애 주입 → 반응 관찰 |
| **장애 사례 회고 (postmortem)** | [case-studies/](../case-studies/) | 카오스로 찾은 부정합을 블레임리스 회고로 |
| **DLQ admin REST API** | 3 service 공통 표준 | [ADR-026](decision-log.md) | replay / discard / dry-run / audit |

→ 이론: `dev-lab/incident-response`, `dev-lab/resilience`

## 학습 순서 제안 (이 레포 기준)

1. **README 90초 데모** → 전체 흐름 감 잡기
2. **[ARCHITECTURE.md](../ARCHITECTURE.md)** → 컴포넌트 / 데이터 흐름
3. **[decision-log.md](decision-log.md)** → 왜 그렇게 했나 (ADR 26개) ← 이 레포의 핵심 학습 자료
4. **위 패턴 표** 에서 관심 패턴 → 코드 + 해당 ADR + dev-lab 이론
5. **[runbook/](runbook/)** → 운영자 관점 (알람 → 대응)
6. **[case-studies/](../case-studies/)** → 실제 부딪힌 사례

> 짝 학습 레포: [dev-lab](https://github.com/ssa1004/dev-lab) (이론) ↔ 이 레포 (구현). 이론에서 "왜"를, 여기서 "실제로 어떻게"를 본다.
