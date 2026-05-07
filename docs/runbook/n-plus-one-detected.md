# n-plus-one-detected

## When this fires
어떤 서비스에서 5분 사이에 N+1 (한 번의 작업에서 같은 모양의 쿼리가 N 번 반복되는 안티패턴 — 1번의 마스터 조회 + N번의 자식 조회) 의심 패턴이 1건 이상 감지되었을 때.

```promql
sum by (application) (increase(n_plus_one_total[5m])) > 0
```

> `slow-query-detector` 모듈 ([modules/slow-query-detector](../../modules/slow-query-detector/)) 이
> 같은 정규화 SQL (리터럴을 `?` 로 치환해 모양만 비교) 이 임계 (기본 5) 번 반복될 때 한 번 카운터를 올린다.
> "5번 도달 시 한 번 카운트" 방식이라 같은 N+1 이 1000번 실행돼도 카운트는 1 — *고유 N+1 사례 발생* 만 잡아낸다.

## Impact
- 단기: 응답 시간이 N 에 비례해 증가. p99 알람과 함께 발화하는 일이 많다.
- 중기: DB 커넥션 풀 점유 시간 증가 → `hikari_pool_saturation` 알람 위험
- 장기: 트래픽이 늘면서 갑자기 무너짐 (개발 단계 통과 후 운영에서 터지는 전형)

## Diagnosis

### 첫 5분

1. **로그**:
   ```logql
   {service_name="$service"} |= "Suspected N+1"
   ```
   메시지 형식: `Suspected N+1 (5 executions): <정규화 SQL>` + 호출자 stack 일부.
   stack 의 사용자 코드 프레임 (Spring/Hibernate 프레임은 거른) → 어떤 컨트롤러/서비스/리포지토리 메서드가 만들었는지 바로 식별.

2. **Slow Query & N+1 대시보드** — `n_plus_one_total` 의 증가율 (rate) 이 어느 application 에서 올라가는지 확인.

3. **Tempo trace** — N+1 이 일어난 직후 trace 하나를 잡아서 같은 SQL 구조의 span 이 N 개 늘어선 모양을 시각적으로 확인. 직관적으로 N+1 임을 보일 때 좋음.

### 가설 트리

- **JPA 지연 로딩 (lazy load) ?** → Repository 에서 `@OneToMany`/`@ManyToOne(fetch = LAZY)` 연관관계를 응답에서 접근. 가장 흔한 원인.
- **루프 안에서 `findById(...)` ?** → 컬렉션을 받아 각 원소마다 별도 쿼리. 보통 코드 리뷰에서 잡히지만 한 번 새어나가면 사라진다.
- **Open-in-View (OSIV — 요청이 끝날 때까지 영속성 컨텍스트를 살려두는 Spring 의 기본 옵션) 가 켜져 있음?** → JSON 직렬화 시점에 lazy load 가 일어남. Spring Boot 기본 ON. 끄면 `LazyInitializationException` 이 나며 명확해짐.

## Mitigation

- `@EntityGraph(attributePaths = "items")` 또는 fetch join (한 쿼리에서 연관 엔티티까지 같이 SELECT) 으로 한 번에 가져오기
- 페이징 + 컬렉션 fetch 충돌 (Hibernate 가 페이징을 메모리에서 처리하게 되는 경고) 은 별도 처리 필요 (`@BatchSize` 로 N 개씩 묶어 IN 절 또는 `@Fetch(SUBSELECT)` 로 서브쿼리)
- 응답에서 그 컬렉션이 *정말* 필요한지 점검 — 필요 없으면 별도 엔드포인트로 분리하거나 projection (필요 컬럼만 뽑는 DTO) 사용
- 대규모 배치 read 는 raw SQL 또는 JPQL constructor expression (DTO 로 직접 매핑되는 JPQL 표현) 이 더 빠를 수 있음

## Post-mortem

`case-studies/` 에 다음 정보로 회고:
- 트리거된 코드 위치 (PR 링크)
- N+1 발생 시 응답 시간 비교 (수정 전 vs 후)
- `@EntityGraph` vs fetch join vs subselect 어떤 방법을 골랐고 왜
- 알람으로 잡은 vs 코드 리뷰로 잡은 — 둘 다 가치 있음. 알람은 *놓친 케이스의 마지막 그물*
