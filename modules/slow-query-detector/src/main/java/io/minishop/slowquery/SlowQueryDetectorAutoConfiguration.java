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
 * 활성 조건:
 *  - DataSource bean 이 있어야 함 (감쌀 대상이 있어야 의미가 있음)
 *  - net.ttddyy.dsproxy 클래스가 클래스패스에 있어야 함 (모듈의 api 의존성으로 들어옴)
 *  - MeterRegistry bean 이 있어야 함 (Spring Boot Actuator + Micrometer 가 셋업되어 있어야 함)
 *
 * 순서 주의: {@code @ConditionalOnBean} 은 후보 bean 이 이미 등록된 다음에만 정확히 평가된다.
 * MeterRegistry 는 actuator 의 MetricsAutoConfiguration 이 만들기 때문에, 그 클래스가
 * 클래스패스에 있을 때 그 뒤로 자동 정렬되어야 한다. 클래스 직접 참조는 actuator 의존성을 강제하므로
 * 문자열 이름으로 참조해 선택적 의존성으로 둔다.
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
     * 서블릿 환경에서 worker thread 가 재사용되면, 트랜잭션 밖에서 실행된 쿼리가 ThreadLocal
     * 에 남아 다음 요청까지 누적되는 leak 가 가능하다 (NPlusOneContext 주석 참고).
     * OncePerRequestFilter 가 클래스패스에 있을 때만 등록 — webflux 등 비-서블릿 환경에선 비활성.
     *
     * 별도 nested {@code @Configuration} 으로 분리한 이유: 바깥 auto-config 가 로드될 때
     * 필터 관련 클래스 (jakarta.servlet, FilterRegistrationBean) 가 로드되지 않도록 하기 위함.
     * non-web 환경 (예: 모듈 단위 컨텍스트 테스트) 에서도 안전하게 평가된다.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = "org.springframework.web.filter.OncePerRequestFilter")
    public static class NPlusOneRequestFilterConfiguration {
        @Bean
        public org.springframework.boot.web.servlet.FilterRegistrationBean<NPlusOneRequestFilter> nPlusOneRequestFilterRegistration() {
            org.springframework.boot.web.servlet.FilterRegistrationBean<NPlusOneRequestFilter> reg =
                    new org.springframework.boot.web.servlet.FilterRegistrationBean<>(new NPlusOneRequestFilter());
            // 가능한 한 마지막에 정리하도록 LOWEST_PRECEDENCE — 다른 필터가 만든 쿼리도 윈도우에 포함되게 한다.
            reg.setOrder(org.springframework.core.Ordered.LOWEST_PRECEDENCE);
            reg.addUrlPatterns("/*");
            return reg;
        }
    }
}
