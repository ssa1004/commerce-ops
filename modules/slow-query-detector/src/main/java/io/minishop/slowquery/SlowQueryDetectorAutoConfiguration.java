package io.minishop.slowquery;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;

/**
 * 의존성을 추가하면 Spring Boot 가 자동으로 이 설정을 적용. `mini-shop.slow-query.enabled=false`
 * 로 끌 수 있다.
 *
 * 활성 조건:
 *  - DataSource bean 이 있어야 함 (감쌀 대상이 있어야 의미가 있음)
 *  - net.ttddyy.dsproxy 클래스가 클래스패스에 있어야 함 (모듈의 api 의존성으로 들어옴)
 *  - MeterRegistry bean 이 있어야 함 (Spring Boot Actuator + Micrometer 가 셋업되어 있어야 함)
 */
@AutoConfiguration(after = DataSourceAutoConfiguration.class)
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
}
