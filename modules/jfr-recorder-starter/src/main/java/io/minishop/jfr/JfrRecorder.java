package io.minishop.jfr;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jdk.jfr.Configuration;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * always-on JFR 레코더. 한 {@link Recording} 을 띄워두고 {@code rollover} 주기마다 dump → 새
 * Recording 시작을 반복한다.
 *
 * <p>운영 데이터에서 검증된 패턴 (Datadog Continuous Profiler / NHN APM / 라인 LINE Profiler):
 * <ol>
 *   <li>continuous Recording (한 번 시작해서 영구 켜둠) — 멈췄다 켰다 하면 그 사이의 데이터가 없다.</li>
 *   <li>chunk 를 일정 주기로 dump → 디스크/스토리지 — 사후 분석 가능한 시간 윈도우 확보.</li>
 *   <li>한 머신에 너무 많이 쌓이지 않게 maxRetained 적용 — 디스크 풀 위험을 차단.</li>
 *   <li>{@link #dump(String) ad-hoc dump} — 알람과 동시에 운영자가 즉시 그 시점의 chunk 를 떨궈
 *       볼 수 있게.</li>
 * </ol>
 *
 * <p>왜 직접 {@link Recording} 을 쓰지 않고 한 번 더 감싸는가: rollover, ad-hoc dump, sensitive
 * event filter, 메트릭 노출 등 *운영 패턴* 을 모듈로 묶어 각 서비스에서 의존성만 추가하면 동작
 * 하게 — slow-query-detector 와 같은 철학.
 */
public class JfrRecorder {

    private static final Logger log = LoggerFactory.getLogger(JfrRecorder.class);

    private static final DateTimeFormatter TS = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);

    /**
     * default/profile 외의 사용자 정의 설정 이름은 받지 않는다 — 잘못된 이름이 들어오면 fall-back
     * 으로 default 를 쓴다.
     */
    private static final List<String> KNOWN_SETTINGS = List.of("default", "profile");

    /**
     * sensitive event filter — JFR 이 기본으로 켜는 이벤트 중 host/address/path 같은 PII 가 들어
     * 갈 가능성이 있는 것들. 운영 표준은 이걸 *발생 시점* 에 끄는 것 (chunk 에 안 들어가게).
     *
     * <p>SocketRead/Write 의 host/port 는 외부 의존 디버깅에 유용하지만, 사용자 트래픽이 직접 찍힐
     * 위험이 있는 환경에선 보호 우선 — 감사 비용보다 사고 비용이 크다.
     */
    private static final List<String> SENSITIVE_EVENTS = List.of(
            "jdk.SocketRead",
            "jdk.SocketWrite",
            "jdk.FileRead",
            "jdk.FileWrite"
    );

    private final JfrRecorderProperties props;
    private final MeterRegistry meterRegistry;
    private final ScheduledExecutorService scheduler;
    private final AtomicReference<Recording> current = new AtomicReference<>();
    private volatile ScheduledFuture<?> rolloverTask;
    private volatile boolean started = false;
    private volatile Instant startedAt;

    public JfrRecorder(JfrRecorderProperties props, MeterRegistry meterRegistry) {
        this.props = props;
        this.meterRegistry = meterRegistry;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "jfr-rollover");
            t.setDaemon(true);
            return t;
        });
        // gauge 는 등록 시점에 Recorder 자기 자신을 strong reference 로 잡아야 한다 — primitive
        // double 을 넘기면 Micrometer 가 WeakReference 로만 들고 있어 GC 후 NaN 으로 보인다.
        // {@link #isStarted()} 의 결과를 매 scrape 마다 평가.
        Gauge.builder("jfr.recording.active", this, r -> r.isStarted() ? 1.0 : 0.0)
                .description("JFR continuous recording 활성 여부 (1=on, 0=off)")
                .register(meterRegistry);
    }

    /**
     * Recording 시작 + 주기적 rollover scheduling. 멱등 — 두 번 호출돼도 한 번만 시작한다.
     *
     * <p>실패 케이스에서 *예외를 throw 하지 않는다* — JFR 은 컨테이너 환경/플랫폼 (예: 일부 GraalVM
     * native image) 에서 비활성일 수 있는데, 그 때문에 사용자 앱 부팅이 깨지면 안 된다. 메트릭과
     * WARN 로그로만 알린다.
     */
    public synchronized void start() {
        if (started) return;
        if (!FlightRecorder.isAvailable()) {
            log.warn("JFR (jdk.jfr.FlightRecorder) is not available on this JVM — JFR recording disabled");
            return;
        }
        try {
            Files.createDirectories(Path.of(props.dumpDirectory()));
            Recording r = newRecording();
            r.start();
            current.set(r);
            startedAt = Instant.now();
            started = true;

            long periodMs = props.rollover().toMillis();
            rolloverTask = scheduler.scheduleAtFixedRate(this::rollover, periodMs, periodMs, TimeUnit.MILLISECONDS);

            log.info("JFR continuous recording started (settings={}, rollover={}, dir={})",
                    props.settings(), props.rollover(), props.dumpDirectory());
        } catch (Exception e) {
            log.warn("JFR start failed: {} — recording disabled", e.getMessage());
        }
    }

    public synchronized void stop() {
        if (!started) return;
        started = false;
        if (rolloverTask != null) rolloverTask.cancel(true);
        scheduler.shutdownNow();
        Recording r = current.getAndSet(null);
        if (r != null) {
            try {
                r.stop();
                r.close();
            } catch (Exception e) {
                log.warn("JFR stop failed: {}", e.getMessage());
            }
        }
    }

    /**
     * ad-hoc dump — 알람이 떴을 때 운영자가 즉시 *지금까지 누적된* 데이터를 파일로 떨굴 때.
     * 현재 Recording 을 멈추지 않는다 (rollover 와 다른 목적).
     *
     * @param tag 파일명에 끼워넣는 임의의 태그. 알람 이름/case-study 슬러그 등.
     * @return dump 된 파일 절대경로. dump 가 불가능하면 null.
     */
    public Path dump(String tag) {
        Recording r = current.get();
        if (r == null) return null;
        Path file = Path.of(props.dumpDirectory(), filename("dump", sanitize(tag)));
        try {
            r.dump(file);
            log.info("JFR ad-hoc dump written: {}", file);
            return file;
        } catch (IOException e) {
            log.warn("JFR dump failed for tag={}: {}", tag, e.getMessage());
            return null;
        }
    }

    /** 마지막 rollover 이후의 chunk 들. 정렬: 새 것 먼저. */
    public List<Path> listChunks() {
        Path dir = Path.of(props.dumpDirectory());
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".jfr"))
                    .sorted(Comparator.comparing(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toMillis();
                        } catch (IOException e) {
                            return 0L;
                        }
                    }, Comparator.reverseOrder()))
                    .toList();
        } catch (IOException e) {
            log.warn("listChunks failed: {}", e.getMessage());
            return List.of();
        }
    }

    public boolean isStarted() {
        return started;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    /** rollover task — 현재 Recording dump → 새 Recording 시작. retention 적용. */
    void rollover() {
        if (!started) return;
        Recording prev = current.get();
        if (prev == null) return;
        try {
            Path file = Path.of(props.dumpDirectory(), filename("chunk", uuid()));
            prev.dump(file);

            Recording next = newRecording();
            next.start();
            if (current.compareAndSet(prev, next)) {
                prev.stop();
                prev.close();
            } else {
                // 다른 호출자가 current 를 바꿨다 — 새로 만든 next 는 즉시 닫는다 (리소스 누수 방지).
                next.stop();
                next.close();
            }

            applyRetention();
            count("rollover", "ok");
        } catch (Exception e) {
            log.warn("JFR rollover failed: {}", e.getMessage());
            count("rollover", "error");
        }
    }

    /** maxRetained 초과 chunk 삭제. 가장 오래된 것부터. */
    private void applyRetention() {
        List<Path> chunks = listChunks();
        if (chunks.size() <= props.maxRetained()) return;
        // listChunks 는 *새 것 먼저* 라 maxRetained 이후가 삭제 대상.
        List<Path> toDelete = chunks.subList(props.maxRetained(), chunks.size());
        for (Path p : toDelete) {
            try {
                Files.deleteIfExists(p);
                count("retention", "deleted");
            } catch (IOException e) {
                log.warn("JFR retention delete failed for {}: {}", p, e.getMessage());
                count("retention", "error");
            }
        }
    }

    private Recording newRecording() {
        Recording r;
        try {
            String name = KNOWN_SETTINGS.contains(props.settings()) ? props.settings() : "default";
            Configuration c = Configuration.getConfiguration(name);
            r = new Recording(c);
        } catch (IOException | ParseException e) {
            log.warn("JFR Configuration({}) load failed — falling back to no-config Recording: {}",
                    props.settings(), e.getMessage());
            r = new Recording();
        }
        r.setName("mini-shop-continuous");
        r.setToDisk(true);
        // 한 Recording 의 최대 보유 길이 — rollover 보다 살짝 길게 둬 rollover 가 실패해도 데이터가
        // 즉시 사라지지 않게.
        r.setMaxAge(props.rollover().plus(Duration.ofMinutes(1)));

        if (props.maskSensitiveEvents()) {
            for (String event : SENSITIVE_EVENTS) {
                // setting 으로 disable — 이벤트 자체가 chunk 에 들어가지 않는다 (post-hoc 마스킹과 다름).
                r.enable(event).withoutThreshold();
                r.disable(event);
            }
        }
        return r;
    }

    private String filename(String kind, String suffix) {
        return "%s-%s-%s.jfr".formatted(kind, TS.format(Instant.now()), suffix);
    }

    private static String uuid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static String sanitize(String tag) {
        if (tag == null || tag.isBlank()) return "manual";
        // 파일명 안전 문자만 허용. 유닉스 path traversal 차단도 겸함.
        String s = tag.replaceAll("[^a-zA-Z0-9_-]", "_");
        return s.length() > 32 ? s.substring(0, 32) : s;
    }

    private void count(String kind, String outcome) {
        meterRegistry.counter("jfr.rollover.events", "kind", kind, "outcome", outcome).increment();
    }

    /** 테스트용 — 현재 활성 Recording 반환. 내부 인터페이스. */
    Recording activeRecordingForTest() {
        return current.get();
    }

    /** 테스트용 — rollover task 동기 실행. */
    List<Path> forceRolloverForTest() {
        rollover();
        return listChunks();
    }

    /** 테스트용 — props 노출. */
    JfrRecorderProperties propertiesForTest() {
        return props;
    }
}
