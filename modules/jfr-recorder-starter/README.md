# jfr-recorder-starter

JFR (Java Flight Recorder — JDK 11+ 표준에 들어 있는 저오버헤드 프로파일러) 을 24/7 항상 켜두고 일정 주기로 chunk 를 디스크에 떨궈, 사고가 났을 때 그 직전 시점의 프로파일을 분석할 수 있게 해주는 Spring Boot starter.

> 의존성만 추가하면 동작 (Spring Boot 3.x 자동 설정, Java 21 toolchain).

## 배경

- 사고 회고에서 가장 답답한 순간: "p99 가 갑자기 튀어 올랐는데 그때 무슨 메서드가 CPU 를 먹고 있었는지 알 수가 없다" — 사후에 재현하기 어려움.
- async-profiler / JFR 을 그때 가서 켜는 방식은 늦음. 한 번의 사고 윈도우를 놓치면 재발할 때까지 기다려야 한다.
- continuous profiling (상시 켠 채 chunk 단위로 보존) 이 일반적인 패턴. JFR 의 default 설정 오버헤드는 ~1% 라 운영에서 부담이 적다.

## 어떻게 동작하나

1. **자동 설정** 이 부팅 시 `JfrRecorder` 를 만들고 `start()` 호출 → 하나의 `Recording` 을 시작.
2. 별도 스케줄러가 `rollover` (기본 5분) 주기마다:
   - 현재 `Recording` 을 `chunk-YYYYMMDD-HHmmss-<uuid>.jfr` 로 dump.
   - 새 `Recording` 시작.
   - `maxRetained` (기본 24개 = 2시간) 초과한 오래된 chunk 삭제.
3. **actuator endpoint** `/actuator/jfr` 로 운영자가 즉시 ad-hoc dump trigger 가능.

```
[JVM] -- 항상 ON --> [Recording] --rollover (5m)--> chunk-...jfr
                                                    chunk-...jfr (최근 24개 유지)
                                                    ...
[Operator] -- POST /actuator/jfr/{tag} --> dump-{tag}-...jfr
```

## 설치 (mavenLocal에서)

```kotlin
// build.gradle.kts
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("io.minishop:jfr-recorder-starter:0.1.0-SNAPSHOT")
}
```

Spring Boot Actuator (또는 micrometer-registry) 가 컨텍스트에 있으면 추가 설정 없이 활성화. JDK 11+ 환경 (Java 21 toolchain 강제).

## 설정

기본값으로 충분하지만, 필요하면 `application.yml`:

```yaml
mini-shop:
  jfr:
    enabled: true                 # 끄려면 false
    rollover: 5m                  # chunk 길이
    max-retained: 24              # 보존 chunk 수 (오래된 것부터 삭제)
    dump-directory: /var/jfr      # chunk 디렉토리
    settings: default             # default(~1% 오버헤드) 또는 profile(~3%, 진단용)
    mask-sensitive-events: false  # PII 보호: SocketRead/Write/FileRead/Write 비활성

management:
  endpoints:
    web:
      exposure:
        include: health, prometheus, jfr   # endpoint 노출 명시
```

## Endpoint

| Endpoint | 동작 |
|---|---|
| `GET /actuator/jfr` | 활성 여부, 시작 시각, 누적 chunk 목록 |
| `POST /actuator/jfr/{tag}` | ad-hoc dump — `dump-<TS>-<sanitized-tag>.jfr` 파일 생성 |

## 노출되는 메트릭

| 메트릭 | 종류 | 의미 |
|---|---|---|
| `jfr.recording.active` | gauge | 1 = 정상 동작, 0 = JFR 사용 불가 |
| `jfr.rollover.events{kind=rollover, outcome=ok\|error}` | counter | rollover 결과 |
| `jfr.rollover.events{kind=retention, outcome=deleted\|error}` | counter | retention 적용 결과 |
| `jfr.upload.events{backend, outcome=ok\|error\|list_error}` | counter | 원격 업로드 결과 (활성 시) |
| `jfr.upload.duration{backend}` | timer | 업로드 소요 시간 (활성 시) |

## 원격 업로드 (S3 / MinIO)

JFR chunk 가 디스크에만 있으면 컨테이너가 죽거나 노드가 빠지면 같이 사라집니다. `JfrChunkUploader` 가 chunk rollover 직후 비동기로 원격 사본을 만들어 컨테이너 종료 직전 데이터까지 보존합니다.

- 기본 disable. 활성화는 `mini-shop.jfr.upload.enabled=true`.
- AWS SDK 가 classpath 에 없으면 자동 noop (의존성 부재로 부팅이 깨지지 않게).
- 사용자 앱에서 `software.amazon.awssdk:s3` 를 직접 implementation 으로 추가하면 활성 가능.

```yaml
mini-shop:
  jfr:
    upload:
      enabled: true
      backend: s3                        # s3 / minio / noop
      bucket: minishop-jfr
      region: us-east-1
      endpoint: ""                       # MinIO 같은 호환 스토리지에서만
      key-prefix: minishop/dev
      pod-id: ""                         # blank → HOSTNAME 환경변수
      access-key: ""                     # blank → DefaultCredentialsProvider (IAM role)
      secret-key: ""
      upload-ad-hoc-dumps: false         # ad-hoc dump 도 업로드할지
```

key 구조: `{prefix}/{podId}/{yyyy/MM/dd}/HHmmss-filename.jfr`. yyyy/MM/dd prefix 로 lifecycle 정책 (예: 90일 후 Glacier 이관) 적용이 쉽습니다. ADR-018 참조.

GCS / Azure Blob / 자체 스토리지를 쓰려면 사용자 앱에서 `JfrChunkUploader` 를 직접 bean 으로 정의하세요 — 자동 등록을 건너뜁니다.

## 보안 / PII

- `mask-sensitive-events: true` 로 켜면 JFR 의 다음 이벤트가 발생 시점에 disable — chunk 에 들어가지 않음:
  - `jdk.SocketRead` / `jdk.SocketWrite` (host/port 노출 위험)
  - `jdk.FileRead` / `jdk.FileWrite` (경로 노출 위험)
- post-hoc 마스킹과 다름 — JFR 분석 도구 (JMC/async-profiler view) 가 그 이벤트를 못 본다 (= 데이터 자체가 없음).
- chunk 가 외부 스토리지로 옮겨지면 그쪽 권한 모델 (S3 / object store) 에 의존 — 본 모듈은 발생 + 보존만 책임지고 전송은 운영팀 영역.

## JFR 분석 도구

- **JDK Mission Control (JMC)** — Oracle 무료 데스크탑 GUI. CPU / Memory Allocation / I/O / GC / Lock Contention 모두 시각화.
- **async-profiler** — JFR 파일을 flame graph 로 변환 (`jfrconv` 도구). 핫 메서드 한눈에.
- **JfrParser (programmatic)** — `jdk.jfr.consumer.RecordingFile` 로 직접 파싱. 자동 분석 잡 (배치) 에 활용.

운영자 가이드는 [docs/runbook/jfr-analysis.md](../../docs/runbook/jfr-analysis.md).

## 테스트

```bash
./gradlew test
```

- `JfrRecorderPropertiesTests` — 기본값 / 명시값 / 잘못된 값 경계
- `JfrRecorderTests` — start / rollover / retention / ad-hoc dump / sanitize / idempotent
- `JfrAutoConfigurationTests` — Spring 자동 설정 wiring (enabled / disabled / actuator 와 함께 / endpoint exposure)

## 설계 결정의 배경

- **JFR (vs async-profiler agent)** — JFR 은 JDK 표준이라 별도 agent 부착 불필요. async-profiler 는 더 정밀 (특히 alloc / wall-clock) 하지만 native 라이브러리 배포가 필요. 기본은 JFR 로 두고, 깊은 분석이 필요할 때 chunk 를 async-profiler 로 변환하는 흐름.
- **JFR Recording 1개 + rollover (vs 짧은 Recording 들의 시퀀스)** — Recording 시작/종료 자체에도 작은 비용이 있어 끝없이 만드는 건 비용 누적. 1개를 길게 들고 rollover 시점에만 스왑.
- **filesystem 기반 (vs OTLP profile signal)** — OTel 의 profile signal 은 alpha. 분석 도구 (JMC / async-profiler) 가 현재 모두 file 기반 — 그쪽 ecosystem 에 맞춤. 추후 OTLP profile 이 stable 화 되면 exporter 추가 검토 (ADR-015 후속).
- **continuous + ad-hoc dump 병행** — continuous 는 미리 알 수 없는 사고를 위한 보험, ad-hoc 은 지금 일어나는 사고를 즉시 떨궈 보기 위한 수단. 알람과 동시에 운영자가 dump 트리거.
