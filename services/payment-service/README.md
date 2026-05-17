# payment-service

결제 처리. 외부 PG (Payment Gateway — 결제사) 는 같은 앱 내부 mock 컨트롤러로 시뮬레이션 (지연·실패율 환경변수로 조절).

## API

| Method | Path | 상태 |
|---|---|---|
| POST | `/payments` | ✅ Step 2 |
| GET | `/payments/{id}` | ✅ Step 2 |
| POST | `/mock-pg/charge` | ✅ Step 2 (외부 PG 시뮬레이션) |
| GET | `/mock-pg/config` | ✅ Step 2 (현재 시뮬레이션 파라미터 조회) |

## 도메인 모델

- `Payment` — id, orderId, userId, amount, status, externalRef, failureReason, attempts, timestamps
- `PaymentStatus` — PENDING / SUCCESS / FAILED

흐름: `Payment.pending(...)` → save (PENDING) → `PgClient.charge(...)` 호출 → 응답에 따라 `markSuccess` 또는 `markFailed`. 결제 거절 시 HTTP 402 (Payment Required — 결제 필요) 응답.

## Mock PG 시뮬레이션

`/mock-pg/charge` 가 외부 PG 를 흉내냄. 환경변수로 행동 조절:

| 변수 | 기본값 | 의미 |
|---|---|---|
| `MOCK_PG_LATENCY_MEAN_MS` | 200 | 평균 지연 (ms) |
| `MOCK_PG_LATENCY_STDDEV_MS` | 50 | 지연 표준편차 (정규분포 σ — 평균에서 얼마나 흩어지는지) |
| `MOCK_PG_FAILURE_RATE` | 0.01 | 5xx 응답 확률 |
| `MOCK_PG_TIMEOUT_RATE` | 0.005 | 의도적 timeout 확률 |
| `MOCK_PG_TIMEOUT_MS` | 30000 | timeout 시 멈출 시간 (ms) |
| `MOCK_PG_ENABLED` | true | mock-pg 컨트롤러 활성화 (운영 환경에선 false) |

PgClient 측 timeout:

| 변수 | 기본값 |
|---|---|
| `PG_CONNECT_TIMEOUT_MS` | 1000 |
| `PG_READ_TIMEOUT_MS` | 5000 |

## 실행

```bash
docker compose -f ../../infra/docker-compose.yml up -d postgres
./gradlew bootRun
```

기본값: `localhost:5432/paymentdb`, 포트 8082.

```bash
# happy path
curl -X POST localhost:8082/payments \
  -H "Content-Type: application/json" \
  -d '{"orderId":1, "userId":42, "amount":39880.00}'

# mock 파라미터 조정해서 실패 시뮬레이션
MOCK_PG_FAILURE_RATE=1.0 ./gradlew bootRun
```

## 메트릭

`/actuator/prometheus` — JVM/HTTP. Phase 2에서 결제 성공률·외부 PG 지연 분리 메트릭 추가.

## 테스트

```bash
./gradlew test
```

- `PaymentServiceApplicationTests` — Testcontainers Postgres (테스트 시작 시 진짜 DB 컨테이너를 띄움) + `@MockitoBean PgClient` (Spring 빈을 가짜로 교체). 결제 정상/실패 경로
- `MockPgControllerTests` — failure-rate=0 일 때 `/mock-pg/charge` 가 항상 OK 응답을 주는지

**Docker 데몬 필요**.

## Step 2 체크리스트

- [x] Spring Boot 프로젝트 초기화
- [x] Flyway 마이그레이션 (`payments`)
- [x] 도메인 + Repository
- [x] PgClient (RestClient) + 타임아웃·에러 처리
- [x] MockPgController (지연/실패/타임아웃 시뮬레이션)
- [x] PaymentService 결제 처리 로직 (성공/실패/예외 분기)
- [x] PaymentController (POST/GET)
- [x] Micrometer Prometheus 노출
- [x] Testcontainers 통합 테스트 + mock-pg 단위 테스트
- [x] Prometheus scrape 활성화 (8082)

## DLQ admin REST API

DLQ 관리 콘솔의 백엔드 8 endpoint. 표준은 ADR-026 (DLQ admin REST API 표준 v2).
notification-hub / billing-platform / bid-ask-marketplace / gpu-job-orchestrator 의 검증된 모양.

payment 특유:
- `DlqSource` = `PAYMENT_CHARGE / PAYMENT_REFUND / PG_CALLBACK / OUTBOX`
- stats 차원 `byCustomer`
- **replay 시 PG 의 `Idempotency-Key` 헤더를 그대로 복사** (billing 패턴) — 두 번 차감 차단.
  응답의 `idempotencyKey` 필드에 사용한 키를 노출해 PG audit 매칭 가능.
- `PAYMENT_REFUND` 액션은 audit 로그에 `risk=high` 표식 (돈 *돌려주는* 동작이라 reviewer 가 더 신중하게 판단).

| Method | Path | scope |
|---|---|---|
| GET | `/api/v1/admin/dlq` | `dlq.read` |
| GET | `/api/v1/admin/dlq/{messageId}` | `dlq.read` |
| POST | `/api/v1/admin/dlq/{messageId}/replay` (`X-Idempotency-Key`) | `dlq.write` |
| POST | `/api/v1/admin/dlq/{messageId}/discard` (`{reason}`) | `dlq.write` |
| POST | `/api/v1/admin/dlq/bulk-replay` (`source` 필수, dry-run 강제) | `dlq.bulk` |
| POST | `/api/v1/admin/dlq/bulk-discard` (`source`+`reason` 필수, dry-run 강제) | `dlq.bulk` |
| GET | `/api/v1/admin/dlq/bulk-jobs/{jobId}` | `dlq.read` |
| GET | `/api/v1/admin/dlq/stats?from=&to=&bucket=PT1H` | `dlq.read` |

권한 / 안전 (ADR-026 공통):
- `X-Admin-Role: DLQ_ADMIN` (또는 `PLATFORM_ADMIN`) 헤더 필요.
- `X-Actor: <user>` 헤더로 audit actor 기록.
- rate limit `admin:dlq:<ip>` scope 별 분당 `read=60 / write=30 / bulk=5`. 초과 시 `429 + Retry-After`.
- `bulk-*` 는 `source` 필수, `confirm=false` 면 항상 dry-run.
- `bulk-discard` 는 hard DELETE 차단 (soft delete + retention).

운영 curl 예시:

```bash
BASE=http://localhost:8082/api/v1/admin/dlq

# 1) 최근 PAYMENT_CHARGE 실패 조회
curl -s -H "X-Admin-Role: DLQ_ADMIN" -H "X-Actor: alice" \
  "$BASE?source=PAYMENT_CHARGE&size=20"

# 2) 단건 replay — admin 의 idempotency 와 PG 의 Idempotency-Key 가 별도
#    응답의 idempotencyKey 가 *PG 에 전달된 키* — PG audit 와 매칭용
curl -s -X POST \
  -H "X-Admin-Role: DLQ_ADMIN" -H "X-Actor: alice" \
  -H "X-Idempotency-Key: $(uuidgen)" \
  "$BASE/<messageId>/replay"

# 3) bulk-replay DRY-RUN (PG charge timeout 들만)
curl -s -X POST \
  -H "X-Admin-Role: DLQ_ADMIN" -H "X-Actor: alice" \
  -H "Content-Type: application/json" \
  -d '{"source":"PAYMENT_CHARGE","errorType":"PG_TIMEOUT","maxMessages":200}' \
  "$BASE/bulk-replay"

# 4) 통계 (byCustomer 차원)
curl -s -H "X-Admin-Role: DLQ_ADMIN" \
  "$BASE/stats?from=2026-05-17T00:00:00Z&to=2026-05-18T00:00:00Z&bucket=PT1H"
```

