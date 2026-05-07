# gc-pause-too-long

## When this fires
어떤 서비스에서 평균 GC pause (Garbage Collection 이 앱을 잠시 멈추는 시간) 가 5분 평균 200ms 를 초과.

```promql
rate(jvm_gc_pause_seconds_sum[5m])
/
clamp_min(rate(jvm_gc_pause_seconds_count[5m]), 0.001)
> 0.2
```

## Impact
- pause 자체가 곧 응답 시간 증가 → p99 응답시간 알람과 동시에 뜨는 일이 흔함
- 누적되면 OOM (Out Of Memory — 메모리 부족으로 JVM 다운) 의 전조

## Diagnosis

### 첫 5분

1. **JVM + HTTP 대시보드** → "GC pause" 패널에서 G1 / ZGC (둘 다 JVM 의 GC 알고리즘 — G1 은 균형형, ZGC 는 sub-ms 지연 최적화) pause 추세
2. **Heap usage by area** 패널 — 시간에 따라 Old Gen (오래 살아남은 객체가 들어가는 영역) 이 우상향이면 메모리 누수 의심
3. 서비스 컨테이너에 attach (실행 중인 JVM 에 진단 도구 붙이기) 가능하면:
   ```
   jcmd <pid> GC.heap_info               # 힙 영역별 사용량
   jcmd <pid> VM.native_memory summary   # JVM 외부 (네이티브) 메모리
   ```
4. JFR (Java Flight Recorder — JDK 내장 프로파일러) 단기 캡처:
   ```
   jcmd <pid> JFR.start name=alarm-investigation duration=2m settings=profile filename=/tmp/alarm.jfr
   ```

### 가설 트리

- **점진적 누수** → Old Gen 우상향 + GC count 도 우상향. heap dump (메모리 스냅샷) (`jmap -dump:format=b,live,file=heap.bin <pid>`) 후 MAT (Eclipse Memory Analyzer — heap dump 분석 도구) 로 큰 retained set (참조 때문에 GC 못 되고 잡혀있는 객체 묶음) 추적.
- **순간 spike** → 일시적 큰 객체 할당. 어떤 요청이 트리거인지 trace 에서 확인 (특정 GET 이 큰 List 를 메모리에 로딩하는 등).
- **Humongous allocation (G1)** → G1 GC 의 region 크기의 50% 를 넘는 단일 객체 — 별도 처리되어 GC 비용이 높음. JFR 에서 humongous allocation 이벤트 확인.
- **잘못된 -Xmx (JVM 최대 힙 크기)** → 컨테이너 메모리 limit 대비 -Xmx 가 적당한지. JVM ergonomics (자동 튜닝) 가 제대로 인지하는지 확인 (`-XX:+PrintFlagsFinal | grep MaxHeap`).

## Mitigation

- 단기: -Xmx 임시 상향 + 인스턴스 재시작
- ZGC 또는 Shenandoah (또 다른 저지연 GC) 전환 검토 (밀리초 미만 pause, 단 처리량 trade-off — 대신 throughput 약간 손해)
- GC 옵션 튜닝: G1ReservePercent (예비 영역 비율), MaxGCPauseMillis (목표 pause 시간) 조정
- 메모리 누수면 영구 fix 필요. 임시 회피책으로 스케줄 재시작 (cron 으로 주기 재시작)

## Post-mortem

`case-studies/` 에:
- 누수 vs 일시적 spike 분류
- JFR / heap dump 핵심 지표 캡처
- GC 옵션 변경 시 어떤 trade-off 를 받아들였는지
