package io.minishop.slowquery;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.ttddyy.dsproxy.support.ProxyDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring 컨텍스트 wiring만 확인. 슬로우/N+1 동작 자체의 검증은 SlowQueryListenerTests에서.
 */
class SlowQueryDetectorAutoConfigurationTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(
					DataSourceAutoConfiguration.class,
					SlowQueryDetectorAutoConfiguration.class
			))
			.withUserConfiguration(MeterRegistryConfig.class)
			.withPropertyValues(
					"spring.datasource.url=jdbc:h2:mem:slowq;DB_CLOSE_DELAY=-1",
					"spring.datasource.driver-class-name=org.h2.Driver"
			);

	@Test
	void dataSourceIsWrappedByProxy() {
		runner.run(ctx -> {
			DataSource ds = ctx.getBean(DataSource.class);
			assertThat(ds).isInstanceOf(ProxyDataSource.class);
			assertThat(ctx).hasSingleBean(SlowQueryListener.class);
		});
	}

	@Test
	void canBeDisabled() {
		runner.withPropertyValues("mini-shop.slow-query.enabled=false")
				.run(ctx -> {
					DataSource ds = ctx.getBean(DataSource.class);
					assertThat(ds).isNotInstanceOf(ProxyDataSource.class);
					assertThat(ctx).doesNotHaveBean(SlowQueryListener.class);
				});
	}

	@Test
	void respectsCustomThresholdProperties() {
		runner.withPropertyValues(
						"mini-shop.slow-query.slow-threshold=500ms",
						"mini-shop.slow-query.n-plus-one-threshold=10"
				)
				.run(ctx -> {
					SlowQueryDetectorProperties props = ctx.getBean(SlowQueryDetectorProperties.class);
					assertThat(props.slowThreshold().toMillis()).isEqualTo(500);
					assertThat(props.nPlusOneThreshold()).isEqualTo(10);
				});
	}

	/**
	 * 회귀 방지: 운영 환경에선 MeterRegistry 를 actuator 의 MetricsAutoConfiguration 이 만든다.
	 * 그쪽이 우리 자동설정보다 *나중에* 평가되면 {@code @ConditionalOnBean(MeterRegistry)} 가
	 * 빈을 못 찾아 자동설정 전체가 조용히 비활성화되는 사고가 났었다 (감지기 자체가 안 붙음).
	 *
	 * 여기서는 사용자 설정으로 MeterRegistry 를 안 주고 actuator 의 자동설정만 추가해서,
	 * 우리 자동설정이 actuator 뒤로 정렬되어 정상 활성화되는지 확인.
	 */
	@Test
	void activatesWhenMeterRegistryComesFromActuatorAutoConfiguration() {
		new ApplicationContextRunner()
				.withConfiguration(AutoConfigurations.of(
						DataSourceAutoConfiguration.class,
						MetricsAutoConfiguration.class,
						SimpleMetricsExportAutoConfiguration.class,
						SlowQueryDetectorAutoConfiguration.class
				))
				.withPropertyValues(
						"spring.datasource.url=jdbc:h2:mem:slowq2;DB_CLOSE_DELAY=-1",
						"spring.datasource.driver-class-name=org.h2.Driver"
				)
				.run(ctx -> {
					assertThat(ctx).hasSingleBean(MeterRegistry.class);
					assertThat(ctx).hasSingleBean(SlowQueryListener.class);
					assertThat(ctx.getBean(DataSource.class)).isInstanceOf(ProxyDataSource.class);
				});
	}

	/**
	 * non-web 컨텍스트에선 NPlusOneRequestFilter 등록을 시도하지 않아야 한다 (servlet API 가
	 * 없는 환경에서 ClassNotFoundException 으로 컨텍스트 시작이 깨지면 안 된다는 회귀 방지).
	 */
	@Test
	void doesNotRegisterServletFilterInNonWebContext() {
		runner.run(ctx ->
				assertThat(ctx).doesNotHaveBean(FilterRegistrationBean.class)
		);
	}

	/**
	 * servlet web 컨텍스트에서는 N+1 ThreadLocal 누수를 막는 필터가 자동으로 등록되어야 한다.
	 */
	@Test
	void registersServletFilterInWebContext() {
		new WebApplicationContextRunner()
				.withConfiguration(AutoConfigurations.of(
						DataSourceAutoConfiguration.class,
						SlowQueryDetectorAutoConfiguration.class
				))
				.withUserConfiguration(MeterRegistryConfig.class)
				.withPropertyValues(
						"spring.datasource.url=jdbc:h2:mem:slowq3;DB_CLOSE_DELAY=-1",
						"spring.datasource.driver-class-name=org.h2.Driver"
				)
				.run(ctx -> {
					assertThat(ctx).hasSingleBean(FilterRegistrationBean.class);
					FilterRegistrationBean<?> reg = ctx.getBean(FilterRegistrationBean.class);
					assertThat(reg.getFilter()).isInstanceOf(NPlusOneRequestFilter.class);
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
