# Design Notes

설계 결정의 *왜*. README는 사용자 관점, 이 문서는 *내가 왜 이렇게 만들었는가*의 기록입니다.

## 1. 어디서 가로채는가 — DataSource 레벨

후보:
- **Hibernate Statistics** — JPA 한정. JDBC/MyBatis 직접 호출은 못 잡음.
- **`JpaRepository` 에 AOP** (메서드 호출 앞뒤에 측정 로직 끼우기) — 인터페이스 메서드 단위. 한 메서드 안의 여러 SQL 은 한 덩어리로 보임.
- **DataSource 프록시** ✅ — 모든 JDBC 트래픽을 같은 자리에서 본다. 가장 낮은 층.

DataSource 를 감싸는 BeanPostProcessor (Spring 이 만든 빈을 후처리하는 훅) 한 줄로 모든 호출 경로 (JPA/JDBC/MyBatis/순수 JDBC) 를 균일하게 측정할 수 있다는 점이 결정적이었다.

## 2. 어떤 라이브러리로 감쌀까 — datasource-proxy (vs p6spy)

| | datasource-proxy | p6spy |
|---|---|---|
| 의존성 | jar 1개 (`net.ttddyy:datasource-proxy`) | 2개 (`p6spy:p6spy` + 별도 설정 파일) |
| 설정 | Java/Spring 자동 | `spy.properties` 파일 + JDBC URL 변경 |
| 활발한 유지 | ✅ (1.10.x, 2024+) | 둔화 |
| 청취자 모델 | `QueryExecutionListener` 인터페이스 | 어펜더식 |

`datasource-proxy`가 BeanPostProcessor와 자연스럽게 결합되고, JDBC URL을 건드리지 않아도 된다. 라이브러리로 배포하기에 진입 장벽이 낮음.

## 3. 트랜잭션 밖에서 N+1 ThreadLocal 이 새는 문제

`NPlusOneContext` 는 ThreadLocal Map (이 스레드만의 변수 저장소) 에 `정규화 SQL → count` 를 누적한다. 트랜잭션이 활성이면 `TransactionSynchronizationManager.registerSynchronization` (Spring 의 트랜잭션 종료 콜백 등록) 으로 종료 시 자동 정리한다.

**문제**: 트랜잭션 밖 쿼리 (예: `@Transactional` 없이 `JdbcTemplate.queryForObject` 직접 호출) 는 정리 트리거가 없어 ThreadLocal 이 누적된다. Tomcat worker thread (요청을 처리하는 워커 스레드, 풀에서 재사용됨) 가 재사용되면 다음 요청에서 잘못된 N+1 false positive (실제 N+1 이 아닌데 N+1 로 오탐) 가 날 수 있다.

**현재 결정**: 받아들인다. 이유 두 가지:
1. N+1 의 거의 100% 는 JPA lazy loading — 즉 트랜잭션 안에서 발생.
2. 운영 환경에서 false positive 1~2 개는 메트릭 노이즈일 뿐, 실제 손해 없음.

**더 엄격하게 가는 방법** (v0.2 후보):
- `OncePerRequestFilter` (요청당 한 번만 실행되는 서블릿 필터) 를 등록해 요청 종료 시 `NPlusOneContext.reset()` 호출.
- 단점: 비웹 스케줄링 잡 (예: outbox poller, reconciliation 같은 백그라운드 작업) 은 커버 못 함. 결국 양쪽 다 필요.

## 4. SQL 정규화 — 정규식 휴리스틱 (vs 진짜 SQL 파서)

후보:
- **JSqlParser** — 진짜 파서 (SQL 을 문법 트리로 변환). 정확하지만 의존성 5MB+, 일부 PostgreSQL 확장 미지원.
- **정규식 휴리스틱** ✅ — 100줄, 의존성 0.

N+1 그루핑 키로는 *문법적 동등* 이 아니라 *모양이 같은가* 만 알면 충분하다. `WHERE id = 42` 와 `WHERE id = 99` 를 같은 키로 묶기만 하면 된다. 그 정도는 숫자/문자열 리터럴을 `?` 로 바꾸는 것으로 충분.

알려진 false-merging (다른 쿼리가 같은 키로 묶이는) 케이스:
- 인라인 SQL 안에 함수 호출이 있고 그 함수의 인자가 다르면, 다른 의미인데 같은 키로 묶일 수 있다 (예: `DATE_TRUNC('day', x)` vs `DATE_TRUNC('hour', x)` — 둘 다 `date_trunc(?, x)` 로 정규화).
- 받아들이는 이유: 이런 SQL 은 거의 항상 다른 모양으로 나타나거나, 같다면 실제로도 N+1 의심 대상.

## 5. 임계 도달 *순간에만* 카운트 (vs 매 호출마다)

```java
if (count == props.nPlusOneThreshold()) {
    nPlusOneCounter.increment();
}
```

`==` 임에 주의. 임계 (예: 5) 번째 실행에서 한 번만 카운트. 6 번째, 7 번째는 *이미 알고 있는 N+1* 이라 카운트하지 않음.

이렇게 두면 `n_plus_one_total / 분` 이 *고유한* N+1 발생 빈도가 된다. 매 호출마다 카운트하면 같은 N+1 이 1000 번 실행되면 1000 이 추가되어 신호가 흐려짐.

## 6. WARN 로그의 stacktrace를 어디까지 보일까

datasource-proxy/Spring/Hibernate/Hikari의 프레임은 거른다. 사용자 코드 프레임만 N개 (기본 8) 표시.

- 너무 길면 로그 폭증
- 너무 짧으면 어떤 메서드가 N+1을 만들었는지 모름
- 8 정도가 사용자 코드 컨트롤러→서비스→레포지토리→JPA를 충분히 보여주는 깊이

## 7. v0.2 (다음 사이클) 후보

- OTel span event 로 슬로우/N+1 attach → trace 화면에서 바로 보이게
- 비웹 백그라운드 스레드용 OncePerRequestFilter 대안 (Spring Modulith 또는 micrometer Observation — Micrometer 의 추상화로, 메트릭/trace/log 를 한 번의 측정에서 다 얻기)
- 스레드별 N+1 그루핑을 트랜잭션 단위 registry 로 추출 (테스트 용이성 향상)
- `@DisableSlowQueryDetection` 메타 어노테이션 — 의도적 fan-out 메서드 (한 번에 N 건 처리하는 게 정상인 메서드) 는 끄기

## 8. 대안으로 검토했지만 채택 안 한 것

- **JFR (Java Flight Recorder) / async-profiler 통합** — 너무 무거움. 이 라이브러리는 *항상 켜두는* 신호용. JFR 은 디깅 도구.
- **Hibernate `query_cache_factory_class` 통계** — Hibernate 한정. 결정 #1 참고.
- **Spring Boot Actuator `/jdbc` 엔드포인트** — 커넥션 풀 통계만 제공, 쿼리 수준 신호 없음.
