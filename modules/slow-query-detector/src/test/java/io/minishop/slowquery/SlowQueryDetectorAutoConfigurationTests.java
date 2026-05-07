package io.minishop.slowquery;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.ttddyy.dsproxy.support.ProxyDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
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

	@Configuration
	static class MeterRegistryConfig {
		@Bean
		MeterRegistry meterRegistry() {
			return new SimpleMeterRegistry();
		}
	}
}
