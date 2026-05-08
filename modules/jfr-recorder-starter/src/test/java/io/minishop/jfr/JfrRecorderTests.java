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
                true, rollover, maxRetained, tempDir.toString(), "default", mask
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
