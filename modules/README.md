# Spring Boot Ops Toolkit

레포 안에서 자체 개발하는 Spring Boot 운영 라이브러리 모음. 3개는 구현 완료 + services/* 에 적용되어 동작 중, 2개는 설계 단계 (README + DESIGN 만 — 정식 구현 전).

| Module | 상태 | 한 줄 설명 |
|---|---|---|
| [slow-query-detector](slow-query-detector/) | ✅ v0.1 — order-service 적용 | JPA/JDBC 슬로우·N+1 쿼리 자동 감지 → 메트릭/로그 (ADR-012) |
| [jfr-recorder-starter](jfr-recorder-starter/) | ✅ v0.1 — order-service 적용 | JFR always-on continuous profiling — rolling chunk + actuator dump + S3/MinIO 자동 업로드 (ADR-015 / ADR-018 / ADR-024) |
| [correlation-mdc-starter](correlation-mdc-starter/) | ✅ v0.1 (Servlet 한정) | OTel Span → SLF4J MDC 자동 동기화 (trace_id / span_id). WebFlux / Kafka / 비즈니스 attribute 는 후속 (ADR-025) |
| [actuator-extras](actuator-extras/) | 📝 설계 (Phase 3 Step 8) | HikariCP / 스레드풀 / 트랜잭션 통계 커스텀 endpoint |
| [chaos-injector](chaos-injector/) | 📝 설계 (Phase 3 Step 9) | 메서드 단위로 지연/실패 주입 (테스트·데모용) |

> 📝 설계 단계 모듈은 디렉토리에 README 만 있고 `src/` 는 없습니다. 의존성으로 추가해도 동작 코드가 없어 사용 불가 — 정식 구현 시점에 v0.1 태그가 붙고 위 표가 ✅ 로 바뀝니다. 상세 단계는 [ROADMAP](../ROADMAP.md) 참조.

## 설계 원칙

1. **AutoConfiguration 우선** — `META-INF/spring/...AutoConfiguration.imports` 로 의존성만 추가하면 자동 활성화
2. **합리적 기본값 (Opinionated Defaults)** — 의존성 추가만으로 바로 동작, 필요시 properties 로 끄기
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
