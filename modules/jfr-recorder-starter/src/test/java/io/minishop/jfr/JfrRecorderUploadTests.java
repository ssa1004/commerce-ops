package io.minishop.jfr;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.minishop.jfr.upload.RecordingChunkUploader;
import jdk.jfr.FlightRecorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 업로더 wiring 동작 검증 — 실제 S3 없이 RecordingChunkUploader fake 로 호출 흐름과 비동기
 * 처리, 실패 격리, ad-hoc dump 의 업로드 토글, /actuator 응답의 remote chunk 노출까지.
 */
@EnabledIf("isJfrAvailable")
class JfrRecorderUploadTests {

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
    private RecordingChunkUploader uploader;
    private JfrRecorder recorder;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        uploader = new RecordingChunkUploader();
    }

    @AfterEach
    void tearDown() {
        if (recorder != null) recorder.stop();
    }

    @Test
    void rolloverTriggersUpload() {
        recorder = newRecorder(false);
        recorder.start();

        List<Path> chunks = recorder.forceRolloverForTest();
        assertThat(chunks).hasSize(1);

        // 업로드는 비동기 — uploadExecutor 단일 스레드라 곧 완료. Awaitility 로 안정적 대기.
        await().atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(uploader.uploaded()).hasSize(1));
        assertThat(uploader.uploaded().get(0).getFileName().toString()).startsWith("chunk-");
    }

    @Test
    void uploadFailureDoesNotBreakSubsequentRollovers() {
        recorder = newRecorder(false);
        recorder.start();

        uploader.failNextUpload();
        recorder.forceRolloverForTest();
        // 두 번째 rollover 의 chunk 는 정상 업로드돼야 함 — 실패가 큐를 죽이면 안 됨.
        recorder.forceRolloverForTest();

        await().atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(uploader.uploaded()).hasSize(1));
        // 첫 번째는 induced 실패라 uploaded 에 안 들어감, 두 번째만 들어감.
    }

    @Test
    void adHocDumpDoesNotUploadByDefault() {
        recorder = newRecorder(false);
        recorder.start();

        Path dumped = recorder.dump("incident-X");
        assertThat(dumped).isNotNull();

        // 잠시 대기 후에도 업로드 안 일어났음을 확인 — ad-hoc 은 *기본 disable*.
        await().pollDelay(Duration.ofMillis(300)).atMost(Duration.ofSeconds(1))
                .untilAsserted(() -> assertThat(uploader.uploaded()).isEmpty());
    }

    @Test
    void adHocDumpUploadsWhenEnabled() {
        recorder = newRecorder(true);
        recorder.start();

        recorder.dump("incident-X");

        await().atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(uploader.uploaded()).hasSize(1));
        assertThat(uploader.uploaded().get(0).getFileName().toString()).startsWith("dump-");
    }

    @Test
    void exposesRemoteListing() {
        recorder = newRecorder(false);
        recorder.start();

        recorder.forceRolloverForTest();
        recorder.forceRolloverForTest();

        await().atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(recorder.listRemoteChunks(10)).hasSize(2));
        assertThat(recorder.uploaderBackend()).isEqualTo("test-fake");
    }

    private JfrRecorder newRecorder(boolean uploadAdHocDumps) {
        JfrRecorderProperties props = new JfrRecorderProperties(
                true, Duration.ofMinutes(1), 24, tempDir.toString(), "default", false);
        return new JfrRecorder(props, meters, uploader, uploadAdHocDumps);
    }
}
