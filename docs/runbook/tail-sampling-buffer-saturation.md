# tail-sampling-buffer-saturation

## When this fires

OTel Collector 의 tail-based sampling 버퍼가 한도에 닿아 trace 가 *의사결정 전에* drop 되고 있을 때.

```promql
sum(rate(otelcol_processor_tail_sampling_new_trace_id_received[5m]))
  -
sum(rate(otelcol_processor_tail_sampling_sampling_decision_timer_latency_count[5m])) > 0
```

또는 더 단순한 신호:

```promql
sum(rate(otelcol_processor_refused_spans{processor="tail_sampling"}[5m])) > 0
```

> tail_sampling 은 `decision_wait` (현재 10s) 동안 trace 의 모든 span 을 메모리에 들고 있는다.
> 동시 trace 수가 `num_traces` (현재 50000) 를 넘으면 가장 오래된 trace 부터 강제 evict 된다.
> evict 된 trace 는 *의사결정 자체를 못 받고* 사라진다 — error / slow 라도 보존되지 않는다.

## Impact

- **단기**: error / slow trace 의 보존율이 100% 가 아니게 된다 — 가장 보고 싶은 trace 가 누락 가능. 디버깅 비용 증가.
- **중기**: collector 메모리 압박이 `memory_limiter` 임계 (75%) 에 닿으면 receiver 가 backpressure 를 응답해 SDK 쪽에서 drop. trace 손실이 *전 구간* 에 걸쳐 발생.
- **장기**: collector OOM 으로 재시작되면 그 사이 모든 신호 (trace/metric/log) 가 유실.

## Diagnosis

### 첫 5분

1. **트래픽이 갑자기 늘었나** — `expected_new_traces_per_sec` (현재 200) 의 *몇 배* 가 들어오는지:
   ```promql
   sum(rate(otelcol_processor_tail_sampling_new_trace_id_received[1m]))
   ```
   200 의 5배 이상이면 단순한 수용 부족. 트래픽이 일시적이면 기다려도 됨, 지속이면 limit 상향.

2. **policy 별 결정 비율** — 어느 policy 가 어떻게 잡고 있는지:
   ```promql
   sum by (policy, sampled) (rate(otelcol_processor_tail_sampling_count_traces_sampled[5m]))
   ```
   `errors` 가 평소 대비 10배 이상이면 *진짜 error 가 늘어난 것* — 이건 sampling 문제가 아니라 *서비스 문제* (e.g. order-error-rate-spike 알람도 같이 떠야 정상).

3. **collector 메모리** — `memory_limiter` 가 활성화됐는지:
   ```promql
   otelcol_processor_refused_spans{processor="memory_limiter"} > 0
   ```
   값이 늘고 있으면 backpressure 발동 중.

### 가설 트리

- **트래픽 폭증 (정상 사용자 증가)** → `num_traces` 상향 (50k → 100k 등). 메모리도 비례 증가하므로 collector container memory 도 같이 올림.
- **decision_wait 너무 김** → 우리 시스템 p99 가 10s 안쪽이면 안전. payment 의 in-doubt 윈도우 (5s 한도) 가 길어지면 trace 도 같이 길어진다 — `decision_wait` 를 줄이지 말고 *p99 자체를 줄이는 게* 정도.
- **새 instrumentation 으로 span 수 폭증** → 한 trace 당 span 수 × num_traces 가 메모리. 새 라이브러리 자동계측이 켜진 직후라면 그쪽 noise span 을 attribute filter 로 줄임.
- **expected_new_traces_per_sec 가 실제와 너무 다름** → 이 값은 hint 일 뿐 hard limit 은 아니지만, hash table sizing 에 쓰이므로 실측치의 1.5~2x 로 두는 게 좋음.

## Mitigation

### 즉시 (5분 안)

- collector container memory limit 상향 (compose `mem_limit`) + 재기동.
- `num_traces` 를 50k → 100k 로 상향 (config reload).
- 트래픽 폭증이 비정상 (DDoS / 봇) 이면 해당 클라이언트 트래픽 차단.

### 단기 (1시간 안)

- `decision_wait` 를 10s → 5s 로 단축 시도. trade-off: 늦게 도착하는 child span 이 누락된 채 결정될 위험. 평소 span lag (request 시작 ~ 마지막 span 도착) 분포를 먼저 보고 결정.
- `baseline-1pct` 의 sampling_percentage 를 1 → 0.5 로 낮춤. error/slow 보존은 그대로, 정상 trace 의 표본만 줄어든다.

### 장기 (PR 단위)

- collector horizontal scale — tail_sampling 은 *동일 trace 의 모든 span 이 같은 collector* 로 가야 동작한다. 단순 round-robin 으로는 불가능하고 `loadbalancing` exporter 의 routing key (= trace_id) 가 필요. 데모 환경엔 단일 collector 로 충분하지만 운영은 보통 2계층 구성:
  ```
  [SDK] → [LB collector (loadbalancing exporter)] → [tail_sampling collector pool] → [Tempo]
  ```
- Datadog / NewRelic 처럼 *클라이언트 측 sampler* 는 그대로 100% 보내고 collector 가 모든 결정을 잡게 하는 게 정석 — 우리도 이미 SDK 는 100% 송신.

## Post-mortem

`case-studies/` 에 다음 정보로 회고:
- 어떤 policy 가 saturate 되었는가 (errors? slow? 둘 다?)
- 트리거 — 트래픽 자연 증가 / 새 배포 / 외부 사고
- num_traces / decision_wait 를 어떻게 조정했고 그 이유
- saturation 동안 *놓친 trace* 를 다른 신호 (메트릭 5xx 카운터, 로그) 로 어떻게 복원했는지
- horizontal scale 결정이 필요했는지 — 이 경계가 보통 "다음 phase" 의 신호

## 관련

- ADR-014 — Tail-based sampling (이 결정의 *왜*).
- 알람: `tail-sampling-buffer-saturation` (이 runbook).
