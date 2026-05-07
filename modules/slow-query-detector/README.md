# slow-query-detector

JPA/JDBC 슬로우 쿼리와 **N+1 패턴**을 자동으로 감지해 Micrometer 카운터 + WARN 로그로 노출하는 Spring Boot starter.

> 의존성만 추가하면 바로 동작합니다 (Spring Boot 3.x 자동 설정).

## 왜 만드나

- 로그 grep으로 슬로우 쿼리 잡기는 사후·수동
- N+1은 운영 중에는 거의 못 잡음 — 개발 단계 통과하면 보이지 않다가 트래픽이 올 때 갑자기 풀 고갈로 터짐
- 라이브러리만 끼우면 메트릭과 알람으로 *자동* 잡히게 만드는 것이 목표

## 어떻게 동작하나

1. **자동 설정**이 컨텍스트의 모든 `DataSource` bean을 [datasource-proxy](https://github.com/jdbc-observations/datasource-proxy)로 감쌉니다.
2. 모든 쿼리는 elapsed time과 함께 `SlowQueryListener`를 거칩니다.
3. 결과:
   - 임계 초과 → `slow_query_total` 카운터 + WARN 로그
   - 같은 정규화 SQL이 한 트랜잭션 안에서 임계 횟수 도달 → `n_plus_one_total` 카운터 + WARN 로그
   - 모든 쿼리는 `query_execution_seconds{outcome=ok|slow}` 타이머에 기록 (분포 측정 가능)

`@Transactional` 안에서 호출되면 트랜잭션 종료 시 ThreadLocal이 자동 정리됩니다 (`TransactionSynchronization.afterCompletion`).

## 설치 (mavenLocal에서)

```kotlin
// build.gradle.kts
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("io.minishop:slow-query-detector:0.1.0-SNAPSHOT")
}
```

Spring Boot Actuator(또는 micrometer-registry)와 DataSource가 컨텍스트에 있으면 추가 설정 없이 활성화됩니다.

## 설정

기본값으로 충분하지만, 필요하면 `application.yml`에서 조절:

```yaml
mini-shop:
  slow-query:
    enabled: true              # 끄려면 false
    slow-threshold: 200ms      # 단일 쿼리 임계
    n-plus-one-threshold: 5    # 같은 패턴 반복 임계
    capture-stacktrace: true   # 호출자 스택 일부를 WARN 로그에 포함
    stacktrace-depth: 8
```

## 노출되는 메트릭

| 메트릭 | 종류 | 의미 |
|---|---|---|
| `slow_query_total` | counter | `slow-threshold` 초과 쿼리 누적 |
| `n_plus_one_total` | counter | N+1 의심 패턴(임계 도달 *순간*) 누적 |
| `query_execution_seconds` | timer (`outcome=ok\|slow`) | 모든 쿼리 실행 시간 분포 |

## SQL 정규화 (N+1 그루핑 키)

같은 모양의 쿼리를 같은 키로 묶기 위해 단순한 정규화를 사용합니다:

| 입력 | 정규화 결과 |
|---|---|
| `SELECT * FROM orders WHERE id = 42` | `select * from orders where id = ?` |
| `SELECT * FROM orders WHERE id = 99` | `select * from orders where id = ?` |
| `SELECT * FROM users WHERE name = 'Alice'` | `select * from users where name = ?` |

production 수준 SQL 파서는 아니지만 N+1 휴리스틱엔 충분합니다 — 대안과 한계는 [DESIGN.md](DESIGN.md) 참고.

## 알려진 한계

- **트랜잭션 밖** 쿼리에는 ThreadLocal이 자동 정리되지 않을 수 있습니다 (서블릿 worker thread 재사용 시 누적). 거의 모든 N+1은 트랜잭션 안에서 발생하므로 실용적 영향은 작지만, 더 엄격하게 가려면 `@RequestScope` 필터를 추가해야 합니다.
- 현재는 OTel span event로 attach하지 않습니다 (v0.2 후보).

## 테스트

```bash
./gradlew test
```

- `SqlNormalizerTests` — 정규화 휴리스틱
- `SlowQueryListenerTests` — slow / N+1 감지 단위
- `SlowQueryDetectorAutoConfigurationTests` — Spring 자동 설정 wiring (DataSource가 ProxyDataSource로 감싸졌는지, `enabled=false`로 끌 수 있는지)

## 설계 결정의 *왜*

[DESIGN.md](DESIGN.md) 참고. 짧게:

- `datasource-proxy` (vs p6spy) — 가벼움, 메이븐 좌표 단일, 활발한 유지
- DataSource 단에서 잡기 (vs Hibernate Statistics) — JPA·MyBatis·JDBC 어떤 경로든 같은 신호
- ThreadLocal + `TransactionSynchronization` (vs WebFilter) — 비웹 스케줄링 잡까지 커버
