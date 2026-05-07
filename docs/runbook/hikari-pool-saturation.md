# hikari-pool-saturation

## When this fires
어떤 서비스에서 `hikaricp_connections_active / hikaricp_connections_max > 0.9` (DB 커넥션 풀의 90% 이상이 사용 중) 이 3분 이상 지속.

```promql
sum by (application, pool) (hikaricp_connections_active)
/
clamp_min(sum by (application, pool) (hikaricp_connections_max), 1)
> 0.9
```

## Impact
- 풀이 100% 에 닿으면 새로운 요청은 커넥션을 잡기 위해 대기 → 곧 timeout → 5xx
- 운영 중 가장 흔한 p99 튐 원인 중 하나. 이 알람은 **임박 신호** (실제 장애 직전에 깜빡여서 미리 손쓸 시간을 벌어주는 용도) 로 동작해야 함.

## Diagnosis

### 첫 5분

1. **JVM + HTTP 대시보드** → Hikari 패널 (active = 사용 중 / idle = 노는 중 / pending = 커넥션 받으려고 줄 서있는 요청 수)
2. **Loki**:
   ```
   {service_name="$service"} |= "Connection is not available"
   ```
3. **Tempo** → 진행 중 trace 중 DB span 이 비정상적으로 긴 trace 검색 → 슬로우 쿼리 후보

### 가설 트리

- **외부 HTTP 호출이 트랜잭션 안에 있나?** — 가장 흔한 안티패턴. 트랜잭션이 시작된 상태에서 외부 호출이 끼어 있으면 그 시간만큼 DB 커넥션을 그대로 쥐고 있음.
  - order-service 는 외부 호출을 트랜잭션 밖으로 꺼낸 구조 (짧은 트랜잭션 → 외부 호출 → 다시 짧은 트랜잭션). 이 패턴이 깨졌는지 코드 점검.
- **슬로우 쿼리?** → 실행 시간이 길어지면 그만큼 커넥션을 오래 쥠. 슬로우 쿼리 로그 또는 Phase 3 `slow-query-detector` 활용.
- **트래픽 자체가 큼?** → 풀 크기가 부족. 임시 상향 후 인스턴스 증설.
- **트랜잭션 누수?** → 일부 경로에서 커밋/롤백 없이 탈출 (예외 처리 빠진 경우 등). 로그에서 "still active" 패턴 검색.

## Mitigation

- 임시 풀 크기 상향: `spring.datasource.hikari.maximum-pool-size` (보통 20~30)
- 의심 코드 경로 핫픽스로 격리: 외부 호출을 `@Transactional` 밖으로 빼는 작은 PR
- 인스턴스 증설로 부하 분산
- 한계는 결국 **DB 자체** — 풀을 무한대로 늘리면 DB 가 먼저 무너지므로 풀 사이즈는 DB 코어 × 2 정도가 상한

## Post-mortem

`case-studies/` 에:
- 풀 점유 시간이 길었던 쿼리/엔드포인트 top 5
- 풀 크기 변경했다면 변경 이유와 수치
- 영구 fix (코드) 와 임시 완화책 (설정) 분리
