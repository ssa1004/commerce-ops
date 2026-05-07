# hikari-pool-saturation

## When this fires
어떤 서비스에서 `hikaricp_connections_active / hikaricp_connections_max > 0.9`이 3분 이상 지속.

```promql
sum by (application, pool) (hikaricp_connections_active)
/
clamp_min(sum by (application, pool) (hikaricp_connections_max), 1)
> 0.9
```

## Impact
- 풀이 100%에 닿으면 새로운 요청은 connection acquire에서 대기 → 곧 timeout → 5xx
- 운영 중 가장 흔한 P99 튐 원인 중 하나. 이 알람은 **임박 신호**로 동작해 실제 장애 직전에 깜빡여야 함.

## Diagnosis

### 첫 5분

1. **JVM + HTTP 대시보드** → Hikari 패널 (active/idle/pending)
2. **Loki**:
   ```
   {service_name="$service"} |= "Connection is not available"
   ```
3. **Tempo** → 활성 trace 중 DB span이 비정상적으로 긴 trace를 검색 → 슬로우 쿼리 후보

### 가설 트리

- **외부 호출이 트랜잭션 안에 있나?** — 가장 흔한 안티패턴. 트랜잭션 시작 후 외부 HTTP 호출이 끼어 있으면 그 시간만큼 커넥션을 잡고 있음.
  - order-service는 외부 호출을 트랜잭션 밖으로 꺼낸 구조 (TransactionTemplate 짧게 → 외부 호출 → 다시 짧게). 이 패턴이 깨졌는지 코드 점검.
- **슬로우 쿼리?** → 실행 시간 ↑로 커넥션 점유 ↑. 슬로우 쿼리 로그 또는 Phase 3 `slow-query-detector` 활용.
- **트래픽 자체가 큼?** → 풀 크기가 부족. 임시 상향 후 인스턴스 증설.
- **트랜잭션 누수?** → 일부 경로에서 commit/rollback 없이 탈출. 로그에서 "still active" 패턴 검색.

## Mitigation

- 임시 풀 크기 상향: `spring.datasource.hikari.maximum-pool-size` (보통 20~30)
- 의심 코드 경로 핫픽스로 격리: 외부 호출을 `@Transactional` 밖으로 빼는 작은 PR
- 인스턴스 증설로 부하 분산
- 한계는 결국 **DB 자체** — 풀 무한대로 늘리면 DB가 먼저 무너지므로 풀 사이즈는 DB 코어 × 2 정도로 상한

## Post-mortem

`case-studies/`에:
- 풀 점유 시간이 길었던 쿼리/엔드포인트 top 5
- 풀 크기 변경했다면 변경 이유와 수치
- 영구 fix(코드)와 임시 mitigation(설정) 분리
