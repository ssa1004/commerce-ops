# gc-pause-too-long

## When this fires
어떤 서비스에서 평균 GC pause 시간이 5분 평균 200ms를 초과.

```promql
rate(jvm_gc_pause_seconds_sum[5m])
/
clamp_min(rate(jvm_gc_pause_seconds_count[5m]), 0.001)
> 0.2
```

## Impact
- pause 자체가 곧 응답 시간 증가 → p99 latency 알람과 동시에 뜨는 일이 흔함
- 누적되면 OOM의 전조

## Diagnosis

### 첫 5분

1. **JVM + HTTP 대시보드** → "GC pause" 패널에서 G1/ZGC pause 추세
2. **Heap usage by area** 패널 — 시간에 따라 Old Gen이 우상향이면 메모리 누수 의심
3. 서비스 컨테이너에 attach 가능하면:
   ```
   jcmd <pid> GC.heap_info
   jcmd <pid> VM.native_memory summary
   ```
4. JFR 단기 캡처:
   ```
   jcmd <pid> JFR.start name=alarm-investigation duration=2m settings=profile filename=/tmp/alarm.jfr
   ```

### 가설 트리

- **점진적 누수** → Old Gen 우상향 + GC count도 우상향. heap dump (`jmap -dump:format=b,live,file=heap.bin <pid>`) 후 MAT 분석 (큰 retained set 추적).
- **순간 spike** → 일시적 큰 객체 할당. 어떤 요청이 트리거인지 trace에서 확인 (특정 GET이 큰 List를 메모리에 로딩하는 등).
- **Humongous allocation (G1)** → 단일 객체가 region size의 50%를 넘을 때 발생. JFR에서 humongous allocation 이벤트 확인.
- **잘못된 -Xmx** → 컨테이너 메모리 limit 대비 -Xmx가 적당한지. JVM ergonomics가 제대로 인지하는지 확인 (`-XX:+PrintFlagsFinal | grep MaxHeap`).

## Mitigation

- 단기: -Xmx 임시 상향 + 인스턴스 재시작
- ZGC 또는 Shenandoah 전환 검토 (sub-ms pause, 단 throughput trade-off)
- GC 옵션 튜닝: G1ReservePercent, MaxGCPauseMillis 조정
- 메모리 누수면 영구 fix 필요. 회피책으로 스케줄 재시작 (cron)

## Post-mortem

`case-studies/`에:
- 누수 vs spike 분류
- JFR/heap dump 핵심 지표 캡처
- GC 옵션 변경 시 어떤 trade-off를 받아들였는지
