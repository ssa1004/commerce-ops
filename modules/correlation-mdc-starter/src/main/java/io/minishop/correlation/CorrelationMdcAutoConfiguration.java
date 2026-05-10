package io.minishop.correlation;

import io.opentelemetry.api.trace.Span;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * 의존성을 추가하면 Spring Boot 가 자동으로 이 설정을 적용한다.
 * {@code mini-shop.correlation.enabled=false} 로 끌 수 있다.
 *
 * <h2>활성 조건</h2>
 * <ul>
 *   <li>OTel API ({@code io.opentelemetry.api.trace.Span}) 가 클래스패스에 있어야 함 — 없는 환경
 *       에서는 자동설정 자체가 평가되지 않아 NoClassDefFoundError 가 안 난다.</li>
 *   <li>Servlet 웹 애플리케이션이어야 함 — WebFlux 분기는 후속 단계.</li>
 *   <li>{@code OncePerRequestFilter} 가 클래스패스에 있어야 함 — spring-web 의존성 가드.</li>
 * </ul>
 *
 * <h2>필터 순서</h2>
 * <p>{@code Ordered.HIGHEST_PRECEDENCE + 10} — 다른 필터가 로그를 찍기 *전에* MDC 가 채워져
 * 있도록 가장 앞쪽에 위치. {@code +10} 마진은 OTel agent 의 자체 propagation 필터가 더 앞에
 * 자리잡을 여지.
 */
@AutoConfiguration
@ConditionalOnClass({Span.class, org.springframework.web.filter.OncePerRequestFilter.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "mini-shop.correlation", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(CorrelationMdcProperties.class)
public class CorrelationMdcAutoConfiguration {

    @Bean
    public FilterRegistrationBean<CorrelationMdcFilter> correlationMdcFilterRegistration(CorrelationMdcProperties props) {
        FilterRegistrationBean<CorrelationMdcFilter> reg =
                new FilterRegistrationBean<>(new CorrelationMdcFilter(props));
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        reg.addUrlPatterns("/*");
        return reg;
    }
}
