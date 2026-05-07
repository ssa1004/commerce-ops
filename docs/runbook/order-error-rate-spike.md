# order-error-rate-spike

## When this fires
order-service의 5xx 비율이 5분 평균 1%를 초과.

```promql
sum(rate(http_server_requests_seconds_count{application="order-service",status=~"5.."}[5m]))
/
clamp_min(sum(rate(http_server_requests_seconds_count{application="order-service"}[5m])), 0.001)
> 0.01
```

> 4xx(409 OUT_OF_STOCK / 402 PAYMENT_DECLINED 등)는 비즈니스 결과라 카운트하지 않습니다.
> 5xx만 진짜 장애로 봅니다.

## Impact
- P1: 주문 실패 → 매출 직격
- 502는 결제 의존성, 503은 재고 의존성 장애를 강하게 시사

## Diagnosis

### 첫 5분

1. **응답 헤더 `X-Order-Outcome`** — `INVENTORY_INFRA` / `PAYMENT_INFRA` 분포를 보면 어느 의존성이 죽는지 즉답
2. **Loki**:
   ```
   {service_name="order-service"} |= "ERROR"
   ```
   recent ERROR 로그 → 어떤 client(InventoryClient/PaymentClient)에서 던졌는지
3. **Tempo** → 실패한 trace를 잡아 의존 서비스 span의 status를 확인 (보통 inventory 또는 payment span이 ERROR)

### 가설 트리

- **inventory-service down?** → 직접 `curl localhost:8083/actuator/health`. 죽었으면 8083을 살리는 게 우선.
- **payment-service down?** → 동일.
- **두 의존 서비스 다 정상?** → order-service 자신의 NullPointerException 등 코드 버그 가능. 로그/스택트레이스 확인.
- **타임아웃 휘말림?** → `PAYMENT_INFRA` outcome이 압도적이면 결제 read timeout 5s에서 끊긴 케이스. 외부 PG 지연 가능성.

## Mitigation

- inventory-service 프로세스 재시작 (1차) → 같은 증상 재발이면 코드/메모리/네트워크 분석
- payment-service 결제 외부 의존성 격리 (mock-pg / 실제 PG 분리)
- 짧은 시간 안에 회복 안 되면: order-service 측에서 `ENABLE_DEGRADED=true` 같은 플래그로 재고 호출 우회 (Phase 4 안건 — 지금은 미구현)

## Post-mortem

`case-studies/`에 다음 정보 포함:
- 문제 발견 시각 vs 알람 발화 시각 (alarm latency)
- 영향 받은 주문 수 (order DB의 FAILED 카운트 추출)
- 의존성별 5xx 분해 (`X-Order-Outcome` 분포)
