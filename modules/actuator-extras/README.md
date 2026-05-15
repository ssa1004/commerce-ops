# actuator-extras

Spring Boot Actuator (Spring Boot 의 운영 상태 노출 모듈 — `/actuator/*` 경로) 에 운영에서 자주 필요한 정보를 추가하는 커스텀 엔드포인트 모음.

> ✅ **v0.1 — 최소 동작 (HikariCP 풀 스냅샷)**: `/actuator/hikari` 가 모든 HikariCP 풀의 active / idle / pending / 설정값을 *지금 이 순간* 스냅샷으로 돌려준다. ThreadPool / Transaction / Dependency ping endpoint 는 후속 단계 — ROADMAP Phase 3 Step 8 에서 확장.

## 배경

- 기본 Actuator 는 `/health`, `/metrics` 정도. 운영 중 *지금 무슨 일이 일어나는지* 알기엔 부족
- Hikari (DB 커넥션 풀) 의 active/idle/pending 상태는 메트릭 (`hikaricp_connections_*`) 으로도 보지만, 메트릭은 스크랩 주기 (15~60s) 의 표본이라 *방금 5초간 막혔던* 짧은 사건을 자주 놓침 → 호출 시점의 라이브 값을 그대로 보고 싶을 때가 있음

## 동작 (v0.1)

- `GET /actuator/hikari` — Spring 컨텍스트의 모든 `HikariDataSource` 를 순회해 풀별 스냅샷 반환
- 읽기 경로: `HikariPoolMXBean` (active / idle / total / pending 라이브 값) + `HikariConfigMXBean` (maxPoolSize / minIdle 설정값)
- 자동 활성: `HikariDataSource` + actuator `@Endpoint` 클래스 조건. `@ConditionalOnAvailableEndpoint` 로 **노출을 켰을 때만** bean 생성 — 운영 환경에서 풀 내부 상태가 인증 없이 새지 않도록 1차 방어
- 경계: Hikari 가 아닌 DataSource 는 조용히 건너뜀 / 풀이 아직 부팅 전이면 (`PoolMXBean` null) 결과에서 제외 — NPE 없이 "아직 안 보임" 으로 처리

### 응답 예시

```json
{
  "pools": [
    {
      "pool": "HikariPool-1",
      "active": 2,
      "idle": 8,
      "total": 10,
      "pending": 0,
      "maxPoolSize": 10,
      "minIdle": 10
    }
  ]
}
```

`pending > 0` 이 지속되면 풀 크기 부족 또는 커넥션 누수 의심 — `maxPoolSize` 가 해석의 기준선.

## Configuration

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health, prometheus, hikari   # hikari 를 명시해야 노출됨
mini-shop:
  actuator-extras:
    enabled: true        # 끄려면 false (노출을 켜둬도 endpoint bean 자체가 안 생김)
```

## 후속 단계 (예정)

| Endpoint | 내용 |
|---|---|
| `/actuator/threadpools` | 모든 `ThreadPoolTaskExecutor` 상태 (active, queue size, rejected count — 큐가 가득 차서 거부된 작업 수) |
| `/actuator/transactions` | 진행 중 트랜잭션 (시작 시간, 호출 스택 일부) |
| `/actuator/dependencies` | DB/Kafka/Redis ping + 최근 응답 시간 |

- 운영 환경 보안 — `@ConditionalOnAvailableEndpoint` 1차 방어 외에, 민감 endpoint 의 Spring Security 권한 가드 가이드 통합

## 설치 (mavenLocal 에서)

```kotlin
// build.gradle.kts
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("io.minishop:actuator-extras:0.1.0-SNAPSHOT")
}
```

의존성만 추가하면 자동 활성. `management.endpoints.web.exposure.include` 에 `hikari` 를 넣어 노출하면 끝. 끄려면 `mini-shop.actuator-extras.enabled=false`.
