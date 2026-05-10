package io.minishop.jfr;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.minishop.jfr.upload.JfrChunkUploader;
import io.minishop.jfr.upload.NoopJfrChunkUploader;
import io.minishop.jfr.upload.S3JfrChunkUploader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring 자동 설정 wiring 만 검증. JFR 동작 자체는 {@link JfrRecorderTests}.
 *
 * <p>JfrRecorder 의 {@code start()} initMethod 가 컨텍스트 시작 시 실제 JFR 을 켠다 → CI 환경에
 * 따라 비활성일 수 있는데, JfrRecorder.start 가 *예외를 throw 하지 않도록* 설계되어 있어 컨텍스트
 * 부팅은 깨지지 않는다. 이 분리가 핵심.
 */
class JfrAutoConfigurationTests {

    @TempDir
    Path tempDir;

    private ApplicationContextRunner baseRunner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JfrAutoConfiguration.class))
                .withUserConfiguration(MeterRegistryConfig.class)
                .withPropertyValues("mini-shop.jfr.dump-directory=" + tempDir);
    }

    @Test
    void registersJfrRecorderWhenMeterRegistryAvailable() {
        baseRunner().run(ctx -> {
            assertThat(ctx).hasSingleBean(JfrRecorder.class);
            JfrRecorder recorder = ctx.getBean(JfrRecorder.class);
            // properties 가 적용됐는지 — dump-directory 가 우리 임시 경로.
            assertThat(recorder.propertiesForTest().dumpDirectory()).isEqualTo(tempDir.toString());
        });
    }

    @Test
    void canBeDisabled() {
        baseRunner().withPropertyValues("mini-shop.jfr.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(JfrRecorder.class));
    }

    @Test
    void respectsCustomProperties() {
        baseRunner().withPropertyValues(
                        "mini-shop.jfr.rollover=1m",
                        "mini-shop.jfr.max-retained=12",
                        "mini-shop.jfr.max-total-size=128MB",
                        "mini-shop.jfr.settings=profile",
                        "mini-shop.jfr.mask-sensitive-events=true"
                )
                .run(ctx -> {
                    JfrRecorderProperties props = ctx.getBean(JfrRecorderProperties.class);
                    assertThat(props.rollover().toMinutes()).isEqualTo(1);
                    assertThat(props.maxRetained()).isEqualTo(12);
                    assertThat(props.maxTotalSize().toMegabytes()).isEqualTo(128);
                    assertThat(props.settings()).isEqualTo("profile");
                    assertThat(props.maskSensitiveEvents()).isTrue();
                });
    }

    /**
     * 회귀 방지 — actuator 의 MetricsAutoConfiguration 이 만든 MeterRegistry 를 우리 자동설정이
     * 잡아내는지 (slow-query-detector 가 같은 사고를 한 번 만나서 명시 회귀로 둔다 — ADR-012 참고).
     */
    @Test
    void activatesWithActuatorMeterRegistry() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        MetricsAutoConfiguration.class,
                        SimpleMetricsExportAutoConfiguration.class,
                        JfrAutoConfiguration.class
                ))
                .withPropertyValues("mini-shop.jfr.dump-directory=" + tempDir)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(MeterRegistry.class);
                    assertThat(ctx).hasSingleBean(JfrRecorder.class);
                });
    }

    /**
     * actuator endpoint 가 노출 허용된 경우 JfrEndpoint 도 등록되어야 한다.
     */
    @Test
    void registersEndpointWhenExposed() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        MetricsAutoConfiguration.class,
                        SimpleMetricsExportAutoConfiguration.class,
                        EndpointAutoConfiguration.class,
                        WebEndpointAutoConfiguration.class,
                        JfrAutoConfiguration.class
                ))
                .withPropertyValues(
                        "mini-shop.jfr.dump-directory=" + tempDir,
                        "management.endpoints.web.exposure.include=jfr"
                )
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(JfrEndpoint.class);
                });
    }

    /**
     * 회귀 방지 — JfrEndpoint.dump(@Selector String tag) 가 actuator 의 `OperationMethodParameters`
     * 검증을 통과해야 한다. 컴파일 시 `-parameters` 플래그가 빠지면 클래스 파일에 MethodParameters
     * attribute 가 없어 *컨텍스트 부팅 자체*가 깨진다 — actuator 가 health check 까지 같이 막혀
     * 운영 사고로 직결.
     *
     * <p>이 테스트는 endpoint 의 메서드를 직접 reflect 해 파라미터 이름이 컴파일러에 의해 보존됐는지
     * 단언한다 — build.gradle.kts 의 `-parameters` compilerArg 가 누군가 지운 순간 빨갛게 떨어진다.
     */
    @Test
    void jfrEndpointDumpMethodRetainsParameterNames() throws NoSuchMethodException {
        java.lang.reflect.Method dump = JfrEndpoint.class.getMethod("dump", String.class);
        java.lang.reflect.Parameter[] params = dump.getParameters();
        assertThat(params).hasSize(1);
        // `-parameters` 가 없으면 파라미터 이름이 "arg0" 로 합성되어 들어온다 — Spring 의
        // OperationMethodParameters 는 이 경우 IllegalStateException 을 던져 컨텍스트 부팅을
        // 통째로 깨뜨린다.
        assertThat(params[0].isNamePresent())
                .as("`-parameters` compile flag must be enabled — JfrEndpoint @Selector "
                        + "needs the original parameter name. Check build.gradle.kts.")
                .isTrue();
        assertThat(params[0].getName()).isEqualTo("tag");
    }

    /**
     * exposure 미허용 시 endpoint bean 자체가 등록되면 안 된다 — 권한 가드 1차 방어.
     */
    @Test
    void doesNotRegisterEndpointWhenNotExposed() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        MetricsAutoConfiguration.class,
                        SimpleMetricsExportAutoConfiguration.class,
                        EndpointAutoConfiguration.class,
                        WebEndpointAutoConfiguration.class,
                        JfrAutoConfiguration.class
                ))
                .withPropertyValues("mini-shop.jfr.dump-directory=" + tempDir)
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(JfrEndpoint.class);
                });
    }

    /**
     * upload 비활성 — backend=noop (또는 빈 값) 일 때 NoopJfrChunkUploader 가 선택되어야.
     * 사용자가 의존성/설정을 안 주는 가장 흔한 경로 — *부팅이 깨지지 않는* 회귀.
     */
    @Test
    void usesNoopUploader_whenUploadDisabled() {
        baseRunner().run(ctx -> {
            assertThat(ctx).hasSingleBean(JfrChunkUploader.class);
            assertThat(ctx.getBean(JfrChunkUploader.class)).isInstanceOf(NoopJfrChunkUploader.class);
        });
    }

    /**
     * upload 활성 + S3 backend — S3JfrChunkUploader 가 선택. region/bucket 만 주면 됨 (정적
     * 키가 없으면 DefaultCredentialsProvider 로 fallback — 본 테스트에선 실제 호출은 없으므로
     * 자격증명 검증은 건너뜀).
     */
    @Test
    void usesS3Uploader_whenUploadActive() {
        baseRunner().withPropertyValues(
                        "mini-shop.jfr.upload.enabled=true",
                        "mini-shop.jfr.upload.backend=s3",
                        "mini-shop.jfr.upload.bucket=test-bucket",
                        "mini-shop.jfr.upload.region=us-east-1",
                        "mini-shop.jfr.upload.access-key=DEV",
                        "mini-shop.jfr.upload.secret-key=DEV"
                )
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(JfrChunkUploader.class);
                    assertThat(ctx.getBean(JfrChunkUploader.class)).isInstanceOf(S3JfrChunkUploader.class);
                });
    }

    /**
     * 사용자가 직접 JfrChunkUploader bean 을 정의했다면 자동 등록을 건너뛰어야 (커스텀 backend
     * — GCS / Azure 등 — 를 사용자가 끼울 수 있게).
     */
    @Test
    void respectsUserProvidedUploaderBean() {
        baseRunner()
                .withUserConfiguration(CustomUploaderConfig.class)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(JfrChunkUploader.class);
                    assertThat(ctx.getBean(JfrChunkUploader.class).backendName()).isEqualTo("custom");
                });
    }

    @Configuration
    static class MeterRegistryConfig {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Configuration
    static class CustomUploaderConfig {
        @Bean
        JfrChunkUploader customUploader() {
            return new JfrChunkUploader() {
                @Override public String upload(java.nio.file.Path localChunk) { return "custom://x"; }
                @Override public java.util.List<String> listRemote(int max) { return java.util.List.of(); }
                @Override public String backendName() { return "custom"; }
            };
        }
    }
}
