# order-error-rate-spike

## When this fires
order-service 의 5xx 비율이 5분 평균 1% 를 초과.

```promql
sum(rate(http_server_requests_seconds_count{application="order-service",status=~"5.."}[5m]))
/
clamp_min(sum(rate(http_server_requests_seconds_count{application="order-service"}[5m])), 0.001)
> 0.01
```

> 4xx (409 OUT_OF_STOCK / 402 PAYMENT_DECLINED 등) 는 비즈니스 결과 (재고 없음·결제사 거절은 시스템 장애가 아님) 라 카운트하지 않습니다.
> 5xx 만 진짜 장애로 봅니다.

## Impact
- P1 (가장 높은 심각도): 주문 실패 → 매출 직격
- 502는 결제 쪽 인프라 장애, 503 은 재고 쪽 인프라 장애를 강하게 시사

## Diagnosis

### 첫 5분

1. **응답 헤더 `X-Order-Outcome`** — `INVENTORY_INFRA` / `PAYMENT_INFRA` 분포를 보면 어느 의존성이 죽는지 즉답
2. **Loki**:
   ```
   {service_name="order-service"} |= "ERROR"
   ```
   최근 ERROR 로그 → 어떤 클라이언트 (InventoryClient/PaymentClient) 에서 던졌는지
3. **Tempo** → 실패한 trace 를 잡아 의존 서비스 span 의 상태를 확인 (보통 inventory 또는 payment span 이 ERROR)

### 가설 트리

- **inventory-service down?** → 직접 `curl localhost:8083/actuator/health` (Spring Boot 의 헬스체크 엔드포인트). 죽었으면 8083 을 살리는 게 우선.
- **payment-service down?** → 동일.
- **두 의존 서비스 다 정상?** → order-service 자체의 NullPointerException 등 코드 버그 가능. 로그/스택트레이스 확인.
- **타임아웃에 휘말림?** → `PAYMENT_INFRA` outcome 이 압도적이면 결제 read timeout 5s 에서 끊긴 케이스. 외부 PG 지연 가능성.

## Mitigation

- inventory-service 프로세스 재시작 (1차 대응) → 같은 증상 재발이면 코드/메모리/네트워크 분석
- payment-service 의 외부 PG 의존성 격리 (mock-pg / 실제 PG 분리해서 어느 쪽이 원인인지 좁힘)
- 짧은 시간 안에 회복 안 되면: order-service 측에서 `ENABLE_DEGRADED=true` 같은 플래그로 재고 호출을 우회하는 축소 모드 (Phase 4 안건 — 지금은 미구현)

## Post-mortem

`case-studies/` 에 다음 정보 포함:
- 문제 발견 시각 vs 알람 발화 시각 (alarm latency — 알람이 얼마나 늦게 떴는지)
- 영향 받은 주문 수 (order DB 의 FAILED 카운트 추출)
- 의존성별 5xx 분해 (`X-Order-Outcome` 분포)
