# slow-query-detector

JPA/JDBC 슬로우 쿼리·N+1 패턴을 자동으로 감지해서 **메트릭/로그/Trace event**로 노출.

## 왜 만드나

- 로그 grep으로 슬로우 쿼리 잡기는 사후·수동
- N+1은 운영 중에는 거의 못 잡음 (개발 단계 통과하면 끝)
- → 라이브러리만 끼우면 메트릭과 알람으로 *자동* 잡히게

## 동작 방식 (계획)

- DataSource를 [datasource-proxy](https://github.com/jdbc-observations/datasource-proxy)로 감싸 모든 쿼리 가로채기
- 트랜잭션 컨텍스트별 쿼리 카운트·시간 누적
- 임계 초과 시:
  - Micrometer 메트릭 (`slow_query_total`, `n_plus_one_total`) 증가
  - WARN 로그 + 쿼리/스택트레이스
  - OTel span event 추가 (Trace에서 바로 보이도록)

## Configuration (예시)

```yaml
mini-shop:
  slow-query:
    enabled: true
    slow-threshold: 200ms
    n-plus-one-threshold: 5     # 같은 쿼리 패턴 5회 이상이면 N+1 의심
    log-level: WARN
    capture-stacktrace: true
```

## Public API

- `@DisableSlowQueryDetection` — 특정 메서드/클래스에서 끄기
- `SlowQueryEvent` — Spring `ApplicationEvent`로도 발행 (커스텀 핸들러 연결 가능)

## TODO

- [ ] AutoConfiguration 골격
- [ ] datasource-proxy 통합
- [ ] N+1 휴리스틱 (같은 쿼리 패턴 카운트)
- [ ] OTel span event 추가
- [ ] inventory-service에서 의도적 N+1 만들어 감지 데모
- [ ] DESIGN.md (왜 datasource-proxy를 골랐나, p6spy 비교)
