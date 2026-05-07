# Spring Boot Ops Toolkit

레포 안에서 자체 개발할 Spring Boot 운영 라이브러리 모음입니다. 현재는 설계 문서 단계이며, Phase 3에서 구현한 뒤 각 서비스에 의존성으로 추가합니다.

| Module | 한 줄 설명 |
|---|---|
| [slow-query-detector](slow-query-detector/) | JPA/JDBC 슬로우·N+1 (한 번 작업에서 같은 모양의 쿼리가 N 번 반복되는 안티패턴) 쿼리를 자동 감지해 메트릭/로그/Trace event 로 노출 |
| [correlation-mdc-starter](correlation-mdc-starter/) | OTel Trace ID ↔ MDC (SLF4J/Logback 의 thread-local 키밸류 — 로그 패턴에 출력 가능) 자동 동기화. 로그·트레이스·메트릭 상관관계 |
| [actuator-extras](actuator-extras/) | HikariCP (DB 커넥션 풀), 스레드풀, 트랜잭션 통계 커스텀 endpoint |
| [chaos-injector](chaos-injector/) | 메서드 단위로 지연/실패를 일부러 주입 (테스트·데모용) |

## 설계 원칙

1. **AutoConfiguration 우선** — `META-INF/spring/...AutoConfiguration.imports` 로 의존성만 추가하면 자동 활성화
2. **합리적 기본값 (Opinionated Defaults)** — 즉시 가치 제공, 필요시 properties 로 끄기
3. **Trace ID 자동 처리 (Zero-touch on Trace ID)** — 사용자가 trace 전파 (서비스 간에 trace 컨텍스트가 따라가도록 헤더에 싣는 일) 를 신경 쓰지 않게
4. **공개 API 최소화** — 호환성을 깰 표면적을 좁게

## 배포

- Phase 3 1차 목표: `mavenLocal()` publish (로컬 머신의 ~/.m2 에 게시)
- Phase 4 이후: GitHub Packages 또는 Maven Central
- 각 모듈 SemVer (semantic versioning — major.minor.patch). 0.x 동안은 자유 형식

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
