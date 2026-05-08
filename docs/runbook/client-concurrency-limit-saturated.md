# client-concurrency-limit-saturated

## When this fires

order-service 의 adaptive concurrency limiter 가 *minLimit 근처* 까지 떨어졌거나 거절율이 임계 초과일 때.

```promql
# limit 이 minLimit 의 2배 이내로 좁혀졌고, in_flight 가 그 limit 에 닿아있을 때.
(
  client_concurrency_limit{application="order-service"} <= 2 *
  on(application, upstream)
  group_left()
  vector(2)   # minLimit 가 1 이라고 가정 — 운영 minLimit 에 맞춰 조정
)
and
(
  client_concurrency_in_flight{application="order-service"} >=
  client_concurrency_limit{application="order-service"}
)
```

또는 더 단순한 신호 — 5xx 중 outcome=upstream_limited 비율:

```promql
sum by (application) (rate(order_orchestration_seconds_count{outcome="upstream_limited"}[5m]))
  /
sum by (application) (rate(order_orchestration_seconds_count[5m]))
  > 0.05
```

> adaptive limiter 가 cascade 차단으로 호출을 즉시 거절하고 있다는 뜻. 이건 *성공* — backend 가 망가지는 걸 막고 있는 중. 그러나 사용자 경험 (5% 가 503) 은 분명 손실 — 원인을 봐야 함.

## Impact

- **단기**: 사용자가 503 + Retry-After:1 응답을 받음. 클라이언트 재시도 로직이 있으면 1s 후 재시도, 그 사이 limiter 가 회복되어 있을 수 있음.
- **중기**: limit 이 minLimit 까지 떨어지면 *대부분의 요청* 이 거절됨 — 사실상 partial outage. 그러나 backend 는 보호받는 중 (이걸 안 했다면 backend 도 같이 죽음).
- **장기**: *왜 backend 가 느려졌는가* 가 답해지지 않으면 limit 이 항상 좁혀진 상태로 유지 → 정상 트래픽까지 거절.

## Diagnosis

### 첫 5분

1. **어느 upstream 인지** — limit 이 좁혀진 쪽이 payment 인지 inventory 인지:
   ```promql
   client_concurrency_limit{application="order-service"}
   ```
   둘 다 좁혀졌다면 *공통 원인* (DB/네트워크/우리쪽 GC) 가능성. 한쪽만이면 그쪽 backend 만 보면 됨.

2. **upstream 의 응답 시간** — 정말로 느려졌는지 확인:
   ```promql
   histogram_quantile(0.99,
     sum by (le, uri) (rate(http_server_requests_seconds_bucket{application="payment-service"}[5m])))
   ```
   p99 가 평소의 5x 이상이면 limit 축소가 *맞는 반응* — backend 보호 작동 중.

3. **Tempo trace** — 같은 시간대 trace 하나 잡아서 *어느 span* 에서 시간이 쓰이는지:
   - DB 쿼리? → slow-query-detector 알람도 떴는지 확인.
   - 외부 의존 (mock-pg / Redis)? → 그쪽 응답 시간.
   - GC pause? → JVM dashboard. JFR chunk 분석으로 점프.

### 가설 트리

- **backend DB 압박** → payment/inventory 의 hikari_pool_saturation 알람 함께 발화? 같이 나면 거의 확실.
- **GC long pause** → backend 의 jvm_gc_pause_too_long. JFR 분석 ([jfr-analysis](jfr-analysis.md)) 로 핫 allocator 식별.
- **외부 의존 (PG mock)** → mock-pg 컨테이너 상태, 네트워크 hop.
- **우리쪽 ramp-up** — 새 배포 직후라면 JIT 가 워밍업 중. 보통 30s ~ 1m 안에 자연 회복. (limit 이 회복되는 그래프가 V 자라면 이 가설.)
- **알람 자체의 false positive** — limit 이 잠깐 좁혀졌다가 회복되는 패턴이 자주 보이면 임계 (현재 minLimit 의 2배) 를 더 보수적으로.

## Mitigation

### 즉시 (5분 안)

- backend 가 명확히 죽었으면 — Roll back 또는 트래픽 차단 (다른 instance 로 우회).
- backend 는 살아있는데 적응이 늦으면 — `min-limit` 을 올림 (예: 1 → 5). 회복 속도 빨라짐 vs cascade 위험 trade-off.
- 운영 사고 시 즉시 롤백: `mini-shop.concurrency.enabled=false` — limiter 끄고 기존 timeout-only 동작으로. *cascade 위험* 이 다시 생기므로 backend 의존 부담을 보면서 결정.

### 단기 (1시간 안)

- backend 의 응답 시간이 *평소* 로 돌아왔는데 limit 만 좁혀진 채 머물면 — Gradient2 의 `smoothing` 파라미터 조정 (기본은 라이브러리 default). limit 회복 속도가 빨라짐.
- backend 의 hikari pool 을 키움 (예: 10 → 20) — 동시 처리 능력 자체를 늘려 limit 축소가 일어나지 않게.

### 장기 (PR 단위)

- backend 의 *왜 느렸는가* 를 case-study 로. JFR chunk 분석 + slow-query / GC / 외부 의존 신호 종합.
- limiter 자체의 회복 속도 / 거절율 SLO 를 정의 — 이 알람의 임계도 그에 맞춰 조정.
- 한 호출 그래프가 여러 단의 limiter 를 거치는 구조라면 backpressure 전파 (Reactive / Project Reactor) 검토.

## Post-mortem

`case-studies/` 에 다음 정보로 회고:
- 트리거된 upstream / limit 가 minLimit 까지 떨어진 시간
- 원인 (backend GC? DB? 외부 의존?)
- limiter 가 backend 를 *얼마나 보호* 했는지 — limiter 가 없었다면 backend 도 같이 죽었을 가능성 평가
- min-limit / 알람 임계 조정이 필요했는지

## 관련

- ADR-016 — Adaptive concurrency limiter (이 결정의 *왜*)
- 다른 runbook: `order-error-rate-spike`, `order-p99-latency-high`, `hikari-pool-saturation` 와 자주 함께 발화
- JFR 분석: [jfr-analysis](jfr-analysis.md)
