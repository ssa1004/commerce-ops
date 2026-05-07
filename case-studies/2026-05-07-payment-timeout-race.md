---
date: 2026-05-07
tags: [timeout, in-doubt, distributed-systems, latency-spike]
severity: P1
duration: ~45m
---

# 결제 호출이 timeout으로 끊기는 동안 결제는 이미 SUCCESS였다

> 외부 PG 지연을 시뮬레이션했더니 order는 FAILED로 보상되는데
> payment 쪽에는 SUCCESS 행이 남는 부정합이 나왔다.
> 이 글은 그 한 시간을 어떻게 잡고 무엇을 배웠는지의 회고.

## 한 줄 요약

`mock-pg` 지연을 평소(200ms)에서 1500ms 로 올렸더니, **order-service 의 payment 호출이 5s 근처에서 read timeout** (응답을 받기 위한 대기 시간 초과) 으로 끊기는 일이 종종 생겼다.
order 는 그 호출을 "실패" 로 받아 보상 (잡아둔 재고 해제) + Order(FAILED) 처리했는데, payment-service 쪽 트랜잭션은 이미 커밋되어 **PaymentStatus=SUCCESS** 로 남아 있었다.
**Order 는 FAILED, Payment 는 SUCCESS** — 같은 `orderId` 에 대해 두 서비스가 서로 다른 진실을 갖게 됐다.

이건 분산 시스템에서 잘 알려진 *"in-doubt"* (호출자는 끊겼는데 피호출자는 작업을 끝내버려 결과를 알 수 없는 구간) 문제. 우리 timeout 설계에 빈틈이 있었다.

---

## 어떻게 터졌나

평소 트래픽 시뮬레이션 (`k6 run load/baseline.js`)을 돌리면서, payment-service에 카오스를 주입했다:

```bash
MOCK_PG_LATENCY_MEAN_MS=1500 \
MOCK_PG_LATENCY_STDDEV_MS=400 \
./gradlew -p services/payment-service bootRun
```

평균 1500ms 정규분포 (표준편차 σ=400) 니까 가끔 2.3s 이상 튀는 응답이 나온다.

5분쯤 지나자 두 알람이 거의 동시에 떴다:

- `order_p99_latency_high` (P2 — 두 번째 심각도)
- `order_error_rate_spike` (P1 — 가장 높은 심각도)

Grafana JVM+HTTP 대시보드에서 order 응답시간 p99 가 800ms → 4.8s 로 솟아오름.
응답 헤더 `X-Order-Outcome` 분포는 `PAYMENT_INFRA` (결제 쪽 인프라 장애 분류) 가 30% 까지 올라옴.

## 1차 진단 — 어디서 시간이 쌓이나

[order-error-rate-spike 런북](../docs/runbook/order-error-rate-spike.md)대로 Loki를 먼저 봤다.

```logql
{service_name="order-service"} |= "ERROR" | json
```

```
2026-05-07 14:32:08 ERROR [a3f.../9b1c...] PaymentClient -
  payment-service unreachable: Read timed out
2026-05-07 14:32:08 WARN  [a3f.../9b1c...] OrderService -
  Payment declined for order 18742: payment-service unreachable: Read timed out
```

trace_id `a3f...` 로 Tempo 에 점프. span tree (이 요청 하나의 호출 흐름과 각 단계 시간):

```
POST /orders                          5,043 ms
├─ INSERT orders                          7 ms
├─ POST /inventories/reserve             68 ms
├─ POST /inventories/reserve             62 ms
├─ POST /payments               5,001 ms  ← read timeout
└─ POST /inventories/release             71 ms  (보상)
└─ POST /inventories/release             65 ms  (보상)
└─ UPDATE orders status=FAILED            5 ms
```

여기까지는 예상대로. 호출이 끊겼고 보상이 돌았다.

## 2차 진단 — payment-service 쪽은 어떻게 됐나

같은 `orderId=18742`로 payment-service 로그를 봤다.

```logql
{service_name="payment-service"} |= "18742"
```

```
2026-05-07 14:32:03 INFO  [b1c.../44f...] PaymentService -
  Charging order=18742 amount=39880.00
2026-05-07 14:32:08 INFO  [b1c.../44f...] PaymentService -
  Payment 9821 (order=18742) marked SUCCESS
2026-05-07 14:32:08 INFO  [b1c.../44f...] PaymentEventPublisher -
  Published PaymentSucceeded for order=18742
```

**14:32:08 — order 는 timeout 으로 끊었는데, 같은 시각에 payment 는 SUCCESS 로 커밋하고 PaymentSucceeded 이벤트까지 발행했다.**

DB에 직접 들어가 확인:

```sql
-- orderdb
SELECT id, status FROM orders WHERE id = 18742;
-- 18742 | FAILED

-- paymentdb
SELECT id, order_id, status, external_ref FROM payments WHERE order_id = 18742;
-- 9821 | 18742 | SUCCESS | pg-7a4f...
```

부정합 확정.

## 왜 이렇게 됐나

타이밍을 정렬해보면:

```
14:32:03.000  order: POST /payments 시작
14:32:03.020  payment: 요청 받음, payments 행 PENDING으로 INSERT
14:32:03.020  payment: PgClient.charge() 호출 → mock-pg에 POST
14:32:08.001  ────────────── order의 read timeout 5,001ms 도달 ──────────────
14:32:08.001  order: ResourceAccessException (Read timed out) → PaymentInfraException
14:32:08.001  order: 보상 시작 (release reserve)
14:32:08.020  payment: mock-pg 가 응답 (1,800ms 지연 끝)
14:32:08.020  payment: pgResponse.success=true → markSuccess
14:32:08.025  payment: 트랜잭션 커밋
14:32:08.027  payment: afterCommit 훅 → PaymentSucceeded publish
```

문제의 본질: order 가 호출을 끊었을 때, payment 쪽에선 "방금 끊긴 그 호출" 이 아직 진행 중이었고, 정상적으로 SUCCESS 커밋까지 갔다.
order 는 그 결과를 모른 채 보상으로 들어간다.

이게 **in-doubt window** — 호출자의 timeout 이 발화한 시점부터 피호출자가 실제로 응답을 끝내는 시점 사이에, 결과를 알 수 없는 구간.

## 우리 timeout 설계의 빈틈

| Layer | 설정값 |
|---|---|
| order → payment (read timeout — 응답 대기) | **5,000 ms** |
| payment → mock-pg (read timeout) | **5,000 ms** |
| mock-pg 지연 (장애 주입 후) | 평균 1,500 ms / 표준편차 400 ms |

호출자 (order) 와 피호출자 (payment) 의 read timeout 이 **같다**.
이러면 mock-pg 가 5s 직전에 응답하는 케이스에서 거의 항상 in-doubt 가 발생한다.

올바른 설계는 호출 체인을 따라 timeout 이 **단조 감소** (호출자가 항상 더 큰 timeout) 해야 한다:

```
order  ─5s→  payment  ─≤4s→  PG
```

호출자의 timeout이 항상 더 커야, 피호출자가 끝까지 돌고 응답을 보낼 시간이 남는다.

## Mitigation

### 단기 (재발 방지)

`payment-service` 의 PG 호출 read timeout 을 **3s** 로 줄이고, `order-service` 의 payment 호출 read timeout 은 **5s** 그대로 유지 (호출자 > 피호출자 의 단조 감소 만들기):

```yaml
# payment-service/application.yml
mini-shop:
  pg:
    read-timeout-ms: ${PG_READ_TIMEOUT_MS:3000}   # 5000 → 3000
```

```yaml
# order-service/application.yml  (변경 없음)
mini-shop:
  payment:
    read-timeout-ms: ${PAYMENT_READ_TIMEOUT_MS:5000}
```

이러면 mock-pg 가 4s 에서 끊긴 케이스에 payment-service 가 먼저 끊고 FAILED 로 응답 → order 는 정상 402 PAYMENT_DECLINED 를 받음. in-doubt 윈도우 자체가 사라진다.

### 더 깊은 fix (장기)

지금 흐름은 동기 REST 이다 보니 timeout 으로 끊긴 결과를 결정적으로 알 방법이 없다. 진짜 보장이 필요하면 **결제를 비동기로** 보내야 한다 — Outbox 로 PaymentRequested 이벤트 발행 → payment-service consumer → PaymentSucceeded/Failed 응답 이벤트 → order-service 는 그것만 신뢰.

이게 [ROADMAP](../ROADMAP.md) 의 Phase 2 Step 3b 동기다. 이번 케이스가 그 작업의 직접적인 정당화가 됐다.

## 일반화 가능한 교훈

1. **호출 체인의 timeout 은 단조 감소해야 한다.**
   호출자 ≥ 피호출자. 같으면 in-doubt 가 거의 항상 발생, 호출자 < 피호출자면 더 심함.
2. **3xx/4xx/5xx 로 분류 안 되는 결과가 항상 존재한다 — "모름".**
   Read timeout, ConnectException 같은 *I/O 실패* 는 "성공인지 실패인지 모름" 이다. 이걸 "실패" 로 단정하고 보상을 쏘면 우리처럼 부정합이 난다.
3. **부정합 위험을 받아들이려면, 모니터링으로 잡을 수 있어야 한다.**
   여기서는 (orderId 같은 외부 키로) order.status ≠ payment.status 인 행을 주기적으로 스캔하는 reconciliation job (정기 정합성 점검 잡) 이 있어야 한다. 후속 작업으로 등록.
4. **이런 경계 케이스가 동기 호출의 본질적 단점이다.** Phase 2 Step 3b 로 가는 이유는 이런 함정을 구조적으로 피하기 위함.

## 후속 작업

- [ ] `payment-service` 의 `PG_READ_TIMEOUT_MS` 기본값 5000 → 3000 변경 (단기 fix)
- [x] **reconciliation 잡**: `payment_inbox` 에 PaymentSucceeded 가 있는데 Order 는 FAILED 인 케이스를 주기적으로 카운터로 노출 — Phase 2 Step 3b 로 구현 (ADR-011, [`ReconciliationJob`](../services/order-service/src/main/java/io/minishop/order/reconciliation/ReconciliationJob.java))
- [ ] [order-error-rate-spike 런북](../docs/runbook/order-error-rate-spike.md) 에 "in-doubt 가능성" 진단 단계 추가
- [ ] Phase 2 Step 3c: 결제를 Kafka 비동기로 (이 케이스가 직접적 동기)

## 참고

- ADR-009 — Outbox 는 order 에만, payment/inventory 는 afterCommit publish ([docs/decision-log.md](../docs/decision-log.md))
- 런북 — [order-error-rate-spike](../docs/runbook/order-error-rate-spike.md)
- "End-to-end Arguments in System Design" (Saltzer, Reed, Clark, 1984) — timeout 과 재시도는 양쪽 끝에서만 진실을 결정한다는 고전 논거
