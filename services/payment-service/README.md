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
