# jfr-analysis (운영자 가이드)

> 알람 runbook 이 아니라 알람을 받고 JFR chunk 를 분석해야 할 때의 작업 가이드.

## 언제 JFR 분석을 시작하나

- `jvm_gc_pause_too_long`, `order_p99_latency_high`, `hikari_pool_saturation` 등 런타임이 원인으로 의심되는 알람이 떴을 때.
- 사고 회고에서 "그 시간대 CPU 가 어디에 쓰였나" 가 핵심 질문일 때.
- 새 배포 후 응답시간 회귀가 보이는데 코드 diff 만으로는 원인이 안 잡힐 때 (JIT 변화, allocation 패턴 변화 등).

## chunk 를 어디서 가져오나

```
# 컨테이너 내부
ls /var/jfr/   # (mini-shop.jfr.dump-directory 기본값은 /tmp/jfr)
chunk-20260508-153000-a1b2c3d4.jfr
chunk-20260508-153500-b2c3d4e5.jfr
...
```

또는 actuator 로 그 시점의 ad-hoc dump 트리거:

```bash
# alert 가 떴을 때 즉시 — 아직 rollover 되지 않은 지금까지의 데이터를 떨군다
curl -X POST http://order-service:8081/actuator/jfr/p99-spike-2026-05-08
# → /var/jfr/dump-20260508-153715-p99-spike-2026-05-08.jfr
```

운영 환경은 보통 chunk 를 S3/MinIO 로 자동 업로드 (사이드카 또는 cron) — 컨테이너가 죽어도 데이터가 살아남게.

## 분석 도구 1 — JDK Mission Control (JMC)

가장 직관적. macOS / Linux / Windows 데스크탑 GUI.

```bash
# JMC 다운로드: https://www.oracle.com/java/technologies/jdk-mission-control.html
jmc chunk-20260508-153000-a1b2c3d4.jfr
```

핵심 페이지 (Automated Analysis Results 좌측 트리):

- **Hot Methods** — CPU 가 어느 메서드에 쓰였는지. p99 spike 의 1차 가설.
- **Memory > Allocation** — 어떤 클래스가 빠르게 할당되는지. allocation rate 가 GC pause 의 원인.
- **GC > Pauses** — pause 길이 분포 + 그 시점 직전의 allocation. `gc-pause-too-long` 알람의 정답지.
- **Lock Instances** — 어떤 객체에 thread 가 길게 wait 했는지. inventory 의 분산락 / Hikari 풀 대기와 자주 매칭.
- **I/O > Socket Read/Write** (mask-sensitive-events=false 인 경우) — 외부 의존 응답 시간 분포.

## 분석 도구 2 — async-profiler flame graph

JMC 가 무겁거나, 한 장의 그림으로 정리해 PR / case-study 에 붙일 때.

```bash
# async-profiler 받기: https://github.com/async-profiler/async-profiler/releases
# JFR → flame graph 변환
./jfrconv -t cpu chunk-20260508-153000-a1b2c3d4.jfr cpu-flame.html
./jfrconv -t alloc chunk-20260508-153000-a1b2c3d4.jfr alloc-flame.html
```

`cpu-flame.html` 을 브라우저로 열면 각 stack frame 의 CPU 사용률을 누적 막대로 본다. 가장 넓은 박스가 핫 패스.

## 분석 도구 3 — programmatic (jfr-parser)

자동 분석 잡 / 대시보드 자동화.

```java
import jdk.jfr.consumer.RecordingFile;

try (RecordingFile rf = new RecordingFile(Path.of("chunk-...jfr"))) {
    while (rf.hasMoreEvents()) {
        var e = rf.readEvent();
        if (e.getEventType().getName().equals("jdk.ExecutionSample")) {
            // sampled stack
            var stack = e.getStackTrace();
            // ... 핫 메서드 집계
        }
    }
}
```

CI 에서 chunk 를 받아 상위 10개 핫 메서드를 GitHub Issue 코멘트로 자동 게시 — 자체 분석 잡을 운영하는 팀들이 흔히 쓰는 패턴.

## 분석 가설 트리

### 응답시간 spike

1. CPU bound? → Hot Methods 상위 10개. 우리 코드 프레임이 있는지 (라이브러리만 있으면 아래 분기).
2. GC bound? → GC pause 분포. allocation rate 와 같이 봐야 인과 보임.
3. I/O / Lock bound? → CPU 가 한가하면 thread 가 어디서 기다리고 있는지. Lock Instances + Java Monitor Wait.

### GC pause 가 길다

1. Old Gen 압박? → Heap 사용량 시계열 + Full GC 빈도. heap dump 도 같이 보면 명확.
2. Humongous allocation? → Allocation 페이지에서 큰 byte[] / char[] 의 호출자 (예: Json 직렬화).
3. CMS/G1 region 단편화 → JFR 의 GC region 통계 페이지.

### 메모리 누수 의심

- Allocation 페이지에서 한 방향으로만 증가하는 클래스 + Old Gen 점유 시간이 같이 늘면 누수.
- 더 정확한 진단은 heap dump (`jcmd <pid> GC.heap_dump`) — JFR 만으론 한계.

## case-study 작성

분석 결과를 `case-studies/YYYY-MM-DD-<slug>.md` 에 기록:
- 알람 trigger / 시간 / 임팩트
- 어떤 chunk 를 봤고, 어떤 도구로 분석했는지
- 핵심 발견 (예: "Jackson `JsonNode` allocation 이 GC pressure 의 60% 차지")
- 수정 PR 링크 + 수정 후 동일 chunk 분석 (회귀 증거)

## 관련

- 모듈: [modules/jfr-recorder-starter](../../modules/jfr-recorder-starter/)
- ADR-015 — JFR continuous profiling
- 다른 runbook: `gc-pause-too-long`, `order-p99-latency-high`, `hikari-pool-saturation` 의 Diagnosis 섹션이 모두 JFR 분석으로 점프한다.
