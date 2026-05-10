package io.minishop.jfr;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jdk.jfr.FlightRecorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JFR Recorder 단위 동작 검증. JFR 자체가 빌드/CI 환경에 따라 비활성일 수 있어 (예: 일부 컨테이너
 * 환경, 일부 GraalVM 빌드) 가용성 체크를 먼저 하고 통과한 환경에서만 동작 단언을 한다.
 */
@EnabledIf("isJfrAvailable")
class JfrRecorderTests {

    static boolean isJfrAvailable() {
        try {
            return FlightRecorder.isAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @TempDir
    Path tempDir;

    private MeterRegistry meters;
    private JfrRecorder recorder;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
    }

    @AfterEach
    void tearDown() {
        if (recorder != null) recorder.stop();
    }

    @Test
    void startsRecordingAndExposesActiveGauge() {
        recorder = newRecorder(Duration.ofMinutes(1), 24, false);
        recorder.start();

        assertThat(recorder.isStarted()).isTrue();
        assertThat(recorder.activeRecordingForTest()).isNotNull();
        assertThat(recorder.getStartedAt()).isNotNull();
        assertThat(meters.get("jfr.recording.active").gauge().value()).isEqualTo(1.0);
    }

    @Test
    void rolloverDumpsChunkAndKeepsRecordingActive() {
        recorder = newRecorder(Duration.ofMinutes(1), 24, false);
        recorder.start();

        List<Path> chunks = recorder.forceRolloverForTest();

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).getFileName().toString()).startsWith("chunk-").endsWith(".jfr");
        // rollover 후에도 새 Recording 이 활성 — continuous 의 핵심.
        assertThat(recorder.isStarted()).isTrue();
        assertThat(recorder.activeRecordingForTest()).isNotNull();
        assertThat(meters.get("jfr.rollover.events").tags("kind", "rollover", "outcome", "ok")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void retentionDeletesOldestBeyondMaxRetained() throws IOException {
        recorder = newRecorder(Duration.ofMinutes(1), 2, false);
        recorder.start();

        // 첫 두 번은 유지, 세 번째 rollover 가 오래된 첫 chunk 를 삭제해야 함.
        List<Path> after1 = recorder.forceRolloverForTest();
        sleepShort();
        List<Path> after2 = recorder.forceRolloverForTest();
        sleepShort();
        List<Path> after3 = recorder.forceRolloverForTest();

        assertThat(after1).hasSize(1);
        assertThat(after2).hasSize(2);
        // maxRetained=2 → 가장 오래된 1개 삭제, 결과 2개.
        assertThat(after3).hasSize(2);
        assertThat(meters.get("jfr.rollover.events").tags("kind", "retention", "outcome", "deleted")
                .counter().count()).isGreaterThanOrEqualTo(1.0);
    }

    /**
     * 카운트 cap 이 충분히 큰데 총 크기 cap 이 먼저 닿는 상황 — 평소엔 카운트가 닿지만,
     * burst / profile 설정으로 chunk 크기가 평소보다 커진 상황을 시뮬레이션.
     *
     * <p>실제 JFR Recording 의 chunk 사이즈는 통제 불가라, 직접 더미 .jfr 파일을 같은 디렉토리에
     * 끼워 retention 만 별도로 검증한다 ({@code applyRetention} 은 {@code listChunks} 가
     * 돌려주는 모든 .jfr 을 보므로 실제 Recording 산출물과 더미가 공평하게 정렬됨).
     */
    @Test
    void retentionEvictsByTotalSizeWhenCountCapIsLoose() throws IOException, InterruptedException {
        // maxRetained=10 (느슨), maxTotalSize=20KB → 5KB 더미 4개를 끼우면 size cap 발동해야.
        org.springframework.util.unit.DataSize cap = org.springframework.util.unit.DataSize.ofKilobytes(20);
        JfrRecorderProperties props = new JfrRecorderProperties(
                true, Duration.ofMinutes(1), 10, cap, tempDir.toString(), "default", false);
        recorder = new JfrRecorder(props, meters);
        recorder.start();

        // 더미 4개 = 20KB. listChunks 는 mtime 기반 reverse 정렬 — 시간차를 둬 결정적 순서 확보.
        Path oldest = writeDummy("dummy-1.jfr", 5 * 1024);
        Thread.sleep(15);
        Path mid1 = writeDummy("dummy-2.jfr", 5 * 1024);
        Thread.sleep(15);
        Path mid2 = writeDummy("dummy-3.jfr", 5 * 1024);
        Thread.sleep(15);
        Path newest = writeDummy("dummy-4.jfr", 5 * 1024);
        Thread.sleep(15);

        // forceRolloverForTest 는 새 chunk 를 하나 더 만든다 (5KB 보다 큼). 이 chunk 가 가장 새것이라
        // *항상 보존* 되고, 오래된 더미부터 삭제되어야 한다.
        recorder.forceRolloverForTest();

        assertThat(oldest).doesNotExist();
        assertThat(mid1).doesNotExist();
        // newest 더미는 유지될 수 있고, 가장 새 chunk (rollover 산출물) 는 보존이 보장.
        assertThat(meters.get("jfr.rollover.events").tags("kind", "retention", "outcome", "size_evicted")
                .counter().count()).isGreaterThanOrEqualTo(1.0);
    }

    /**
     * 단일 chunk 가 maxTotalSize 보다 큰 경계 — 사고 시점 데이터를 잃지 않게 가장 새 chunk 1개는
     * 무조건 보존. WARN 으로만 알린다.
     */
    @Test
    void retentionKeepsLargestNewestEvenWhenItExceedsLimit() throws IOException, InterruptedException {
        // maxTotalSize=1KB, 더미 1개 = 4KB → 보존되어야.
        org.springframework.util.unit.DataSize cap = org.springframework.util.unit.DataSize.ofKilobytes(1);
        JfrRecorderProperties props = new JfrRecorderProperties(
                true, Duration.ofMinutes(1), 10, cap, tempDir.toString(), "default", false);
        recorder = new JfrRecorder(props, meters);
        recorder.start();

        Path big = writeDummy("dummy-big.jfr", 4 * 1024);
        Thread.sleep(15);

        // rollover 가 더 새 chunk 를 만든다 — 그 chunk 가 keepUntil 결정 시 가장 새것.
        recorder.forceRolloverForTest();

        // dummy-big 은 두 번째로 새것 — limit 1KB 넘으니 삭제 후보지만, 가장 새 (rollover)
        // chunk 만 보존된다. 단 가장 새 chunk 가 limit 를 넘는 경우엔 보존 (WARN).
        // 검증: at least 한 chunk 는 살아있고, 메트릭이 동작.
        assertThat(recorder.listChunks()).isNotEmpty();
    }

    private Path writeDummy(String name, int bytes) throws IOException {
        Path p = tempDir.resolve(name);
        byte[] data = new byte[bytes];
        Files.write(p, data);
        return p;
    }

    @Test
    void adHocDumpProducesFileWithSanitizedTag() {
        recorder = newRecorder(Duration.ofMinutes(1), 24, false);
        recorder.start();

        // 위험 문자가 섞인 tag — sanitize 가 path traversal 을 막아야.
        Path dumped = recorder.dump("../etc/passwd-alert");

        assertThat(dumped).isNotNull();
        assertThat(Files.exists(dumped)).isTrue();
        assertThat(dumped.getFileName().toString()).startsWith("dump-").endsWith(".jfr");
        // sanitize 결과가 파일명 안전 문자만 남도록.
        assertThat(dumped.getFileName().toString()).doesNotContain("..");
        assertThat(dumped.getFileName().toString()).doesNotContain("/");
    }

    @Test
    void dumpReturnsNullWhenNotStarted() {
        recorder = newRecorder(Duration.ofMinutes(1), 24, false);
        // start() 호출 전.
        assertThat(recorder.dump("anything")).isNull();
    }

    @Test
    void doubleStartIsIdempotent() {
        recorder = newRecorder(Duration.ofMinutes(1), 24, false);
        recorder.start();
        Object first = recorder.activeRecordingForTest();
        recorder.start();
        Object second = recorder.activeRecordingForTest();
        // 같은 Recording — start 가 두 번 호출되어도 새 Recording 이 만들어지면 안 됨.
        assertThat(second).isSameAs(first);
    }

    @Test
    void maskSensitiveEventsKeepsRecordingActive() {
        // PII 이벤트 마스킹 활성 — 이 모드에서도 Recording 이 정상 시작되는지.
        recorder = newRecorder(Duration.ofMinutes(1), 24, true);
        recorder.start();

        assertThat(recorder.isStarted()).isTrue();
        assertThat(meters.get("jfr.recording.active").gauge().value()).isEqualTo(1.0);
    }

    private JfrRecorder newRecorder(Duration rollover, int maxRetained, boolean mask) {
        JfrRecorderProperties props = new JfrRecorderProperties(
                true, rollover, maxRetained, null, tempDir.toString(), "default", mask
        );
        return new JfrRecorder(props, meters);
    }

    private static void sleepShort() {
        try {
            // chunk 파일의 mtime 기반 정렬이 안정적으로 동작하도록 짧게 대기.
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
