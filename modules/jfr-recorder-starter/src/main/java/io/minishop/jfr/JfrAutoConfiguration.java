package io.minishop.jfr;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 의존성 추가만으로 자동 활성화. {@code mini-shop.jfr.enabled=false} 로 끌 수 있다.
 *
 * <p>활성 조건:
 * <ul>
 *   <li>{@code MeterRegistry} bean 이 있어야 함 — 메트릭 노출 대상.</li>
 *   <li>{@code jdk.jfr.FlightRecorder} 클래스가 있어야 함 (JDK 11+ — Java 21 toolchain 강제).</li>
 *   <li>{@code mini-shop.jfr.enabled} 가 true (기본).</li>
 * </ul>
 *
 * <p>{@code afterName} 으로 actuator metric 자동설정을 *문자열 이름* 으로 적는 이유는
 * slow-query-detector 와 같은 패턴 — actuator 의존성이 없는 환경 (예: 슬림 통합 테스트 컨텍스트)
 * 에서도 NoClassDefFoundError 없이 평가되도록 *선택적 (optional)* 의존으로 둔다.
 */
@AutoConfiguration(
        afterName = {
                "org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration",
                "org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration"
        }
)
@ConditionalOnClass(name = {"jdk.jfr.FlightRecorder", "io.micrometer.core.instrument.MeterRegistry"})
@ConditionalOnBean(MeterRegistry.class)
@ConditionalOnProperty(prefix = "mini-shop.jfr", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(JfrRecorderProperties.class)
public class JfrAutoConfiguration {

    @Bean(initMethod = "start", destroyMethod = "stop")
    public JfrRecorder jfrRecorder(JfrRecorderProperties props, MeterRegistry meterRegistry) {
        return new JfrRecorder(props, meterRegistry);
    }

    /**
     * actuator endpoint — actuator 의존성이 있고 endpoint exposure 가 허용된 경우에만 등록.
     * {@code @ConditionalOnAvailableEndpoint} 가 management.endpoints.web.exposure.include 까지
     * 함께 검사하므로, 운영자가 endpoint 를 명시적으로 노출하지 않으면 bean 생성도 안 됨 — 권한
     * 가드의 1차 방어선.
     */
    @Bean
    @ConditionalOnClass(name = "org.springframework.boot.actuate.endpoint.annotation.Endpoint")
    @ConditionalOnAvailableEndpoint(endpoint = JfrEndpoint.class)
    public JfrEndpoint jfrEndpoint(JfrRecorder recorder) {
        return new JfrEndpoint(recorder);
    }
}
