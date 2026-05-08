package io.minishop.jfr;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
                        "mini-shop.jfr.settings=profile",
                        "mini-shop.jfr.mask-sensitive-events=true"
                )
                .run(ctx -> {
                    JfrRecorderProperties props = ctx.getBean(JfrRecorderProperties.class);
                    assertThat(props.rollover().toMinutes()).isEqualTo(1);
                    assertThat(props.maxRetained()).isEqualTo(12);
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

    @Configuration
    static class MeterRegistryConfig {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
