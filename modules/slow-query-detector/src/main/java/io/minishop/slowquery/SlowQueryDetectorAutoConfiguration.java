package io.minishop.slowquery;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * 의존성을 추가하면 Spring Boot 가 자동으로 이 설정을 적용. `mini-shop.slow-query.enabled=false`
 * 로 끌 수 있다.
 *
 * <p>활성 조건:
 * <ul>
 *   <li>DataSource bean 이 있어야 함 — 감쌀 대상이 있어야 의미가 있음.</li>
 *   <li>{@code net.ttddyy.dsproxy} 클래스가 클래스패스에 있어야 함 — 모듈의 api 의존성으로 자동 포함.</li>
 *   <li>MeterRegistry bean 이 있어야 함 — Spring Boot Actuator + Micrometer 가 셋업된 상태.</li>
 * </ul>
 *
 * <p>순서 주의: {@code @ConditionalOnBean} 은 *후보 bean 이 이미 등록된 시점* 에만 정확히 평가된다 (그 전에
 * 평가하면 "아직 없음" 으로 보고 건너뛴다). MeterRegistry 는 actuator 의 MetricsAutoConfiguration 이
 * 만들기 때문에, 본 자동 설정을 그 뒤로 정렬해야 한다.
 *
 * <p>그래서 actuator 클래스를 {@code afterName} 으로 *문자열 이름* 으로 적는다. 클래스로 직접 참조하면
 * 컴파일 단계에서 actuator 의존성이 강제되는데, 본 모듈은 actuator 없는 환경 (예: 테스트용 슬림
 * 컨텍스트) 에서도 안전하게 평가되어야 하므로 선택적 (optional) 의존성으로 둔다.
 */
@AutoConfiguration(
        after = DataSourceAutoConfiguration.class,
        afterName = {
                "org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration",
                "org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration"
        }
)
@ConditionalOnClass(name = {
        "net.ttddyy.dsproxy.support.ProxyDataSource",
        "io.micrometer.core.instrument.MeterRegistry"
})
@ConditionalOnBean({DataSource.class, MeterRegistry.class})
@ConditionalOnProperty(prefix = "mini-shop.slow-query", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(SlowQueryDetectorProperties.class)
public class SlowQueryDetectorAutoConfiguration {

    @Bean
    public SlowQueryListener slowQueryListener(SlowQueryDetectorProperties props, MeterRegistry meterRegistry) {
        return new SlowQueryListener(props, meterRegistry);
    }

    @Bean
    public static DataSourceProxyPostProcessor dataSourceProxyPostProcessor(SlowQueryListener listener) {
        // BeanPostProcessor (다른 빈을 만든 직후에 가로채는 훅) 는 다른 빈보다 먼저 등록되어야 하므로
        // static 팩토리 메서드 (Spring 이 의존성 그래프를 따지기 전에 만들 수 있게) 로 선언.
        return new DataSourceProxyPostProcessor(listener);
    }

    /**
     * 서블릿 환경 전용 — 매 요청 끝에 ThreadLocal 을 비우는 필터를 등록한다 (배경: {@link NPlusOneRequestFilter}
     * Javadoc — worker thread 재사용으로 인한 카운트 누수).
     *
     * <p>왜 nested {@code @Configuration} 으로 분리했나:
     * <ul>
     *   <li>바깥 auto-config 가 로드될 때 jakarta.servlet / FilterRegistrationBean 가 함께 로드되지 않도록 격리.</li>
     *   <li>덕분에 webflux 나 non-web 컨텍스트 (예: 모듈 단위 슬림 테스트) 에서도 NoClassDefFoundError 없이
     *       평가된다 — 조건이 안 맞으면 nested 자체가 통째로 건너뛰어진다.</li>
     * </ul>
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = "org.springframework.web.filter.OncePerRequestFilter")
    public static class NPlusOneRequestFilterConfiguration {
        @Bean
        public org.springframework.boot.web.servlet.FilterRegistrationBean<NPlusOneRequestFilter> nPlusOneRequestFilterRegistration() {
            org.springframework.boot.web.servlet.FilterRegistrationBean<NPlusOneRequestFilter> reg =
                    new org.springframework.boot.web.servlet.FilterRegistrationBean<>(new NPlusOneRequestFilter());
            // LOWEST_PRECEDENCE = 다른 모든 필터가 끝난 *뒤* 에 finally 블록을 만난다 → 다른 필터가 만든 쿼리까지 같은 윈도우에 포함된다.
            reg.setOrder(org.springframework.core.Ordered.LOWEST_PRECEDENCE);
            reg.addUrlPatterns("/*");
            return reg;
        }
    }
}
