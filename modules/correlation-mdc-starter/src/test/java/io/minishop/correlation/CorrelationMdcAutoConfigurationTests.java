package io.minishop.correlation;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 자동설정의 *조건* 만 검증 — 실제 동작 (Span → MDC) 은 {@link CorrelationMdcFilterTests} 에서.
 */
class CorrelationMdcAutoConfigurationTests {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CorrelationMdcAutoConfiguration.class));

    @Test
    void registersFilterByDefault() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(FilterRegistrationBean.class);
            assertThat(ctx).hasSingleBean(CorrelationMdcProperties.class);
            FilterRegistrationBean<?> reg = ctx.getBean(FilterRegistrationBean.class);
            assertThat(reg.getFilter()).isInstanceOf(CorrelationMdcFilter.class);
            // 가장 앞쪽에 위치 — 다른 필터의 로그가 trace_id 를 함께 찍을 수 있게.
            assertThat(reg.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 10);
        });
    }

    @Test
    void respectsCustomMdcKeys() {
        runner.withPropertyValues(
                        "mini-shop.correlation.trace-id-key=tid",
                        "mini-shop.correlation.span-id-key=sid")
                .run(ctx -> {
                    CorrelationMdcProperties props = ctx.getBean(CorrelationMdcProperties.class);
                    assertThat(props.traceIdKey()).isEqualTo("tid");
                    assertThat(props.spanIdKey()).isEqualTo("sid");
                });
    }

    @Test
    void disabledViaProperty() {
        runner.withPropertyValues("mini-shop.correlation.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(CorrelationMdcFilter.class));
    }

    @Test
    void notActivatedInNonWebContext() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CorrelationMdcAutoConfiguration.class))
                .run(ctx -> assertThat(ctx).doesNotHaveBean(CorrelationMdcFilter.class));
    }
}
