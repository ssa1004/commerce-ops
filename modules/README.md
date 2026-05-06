# Spring Boot Ops Toolkit

레포 안에서 자체 개발하는 Spring Boot 운영 라이브러리 모음. 각 서비스에 의존성으로 추가하여 사용.

| Module | 한 줄 설명 |
|---|---|
| [slow-query-detector](slow-query-detector/) | JPA/JDBC 슬로우·N+1 쿼리를 자동 감지해 메트릭/로그/Trace event로 노출 |
| [correlation-mdc-starter](correlation-mdc-starter/) | OTel Trace ID ↔ MDC 자동 동기화. 로그·트레이스·메트릭 상관관계 |
| [actuator-extras](actuator-extras/) | HikariCP, 스레드풀, 트랜잭션 통계 커스텀 endpoint |
| [chaos-injector](chaos-injector/) | 메서드 단위 지연/실패 주입 (테스트·데모용) |

## 설계 원칙

1. **AutoConfiguration 우선** — `META-INF/spring/...AutoConfiguration.imports`로 자동 활성화
2. **Opinionated Defaults** — 즉시 가치 제공, 필요시 properties로 끄기
3. **Zero-touch on Trace ID** — 사용자가 trace 전파를 신경 쓰지 않게
4. **Public API 최소화** — 깨질 표면적을 좁게

## 배포

- Phase 3 1차 목표: `mavenLocal()` publish
- Phase 4 이후: GitHub Packages 또는 Maven Central
- 각 모듈 SemVer (0.x 동안 free-form)

## 모듈 구조 템플릿

```
modules/<module-name>/
├── build.gradle.kts
├── README.md             # 사용자용 README + 설계 의도
├── DESIGN.md             # 깊은 설계 노트 (왜 이렇게 만들었나)
├── src/main/java/...
├── src/main/resources/
│   └── META-INF/spring/
└── src/test/java/...
```
