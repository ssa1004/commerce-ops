package io.minishop.actuator;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring 자동 설정 wiring 만 검증. endpoint 동작 자체는 {@link HikariEndpointTests}.
 */
class ActuatorExtrasAutoConfigurationTests {

    private ApplicationContextRunner runnerWithDataSource() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DataSourceAutoConfiguration.class,
                        EndpointAutoConfiguration.class,
                        WebEndpointAutoConfiguration.class,
                        ActuatorExtrasAutoConfiguration.class
                ))
                // 인메모리 H2 — DataSourceAutoConfiguration 이 HikariDataSource 를 만든다.
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:actuator-extras-test;DB_CLOSE_DELAY=-1",
                        "spring.datasource.driver-class-name=org.h2.Driver"
                );
    }

    /**
     * exposure 에 hikari 가 포함되면 endpoint bean 이 등록되어야 한다.
     */
    @Test
    void registersEndpointWhenExposed() {
        runnerWithDataSource()
                .withPropertyValues("management.endpoints.web.exposure.include=hikari")
                .run(ctx -> assertThat(ctx).hasSingleBean(HikariEndpoint.class));
    }

    /**
     * exposure 미허용 시 endpoint bean 자체가 등록되면 안 된다 — 운영 환경에서 풀 내부 상태가
     * 의도치 않게 새지 않도록 하는 1차 방어 (jfr-recorder-starter 와 같은 정책).
     */
    @Test
    void doesNotRegisterEndpointWhenNotExposed() {
        runnerWithDataSource()
                .run(ctx -> assertThat(ctx).doesNotHaveBean(HikariEndpoint.class));
    }

    /**
     * mini-shop.actuator-extras.enabled=false 면 노출을 켜뒀어도 endpoint 가 등록되지 않는다.
     */
    @Test
    void canBeDisabled() {
        runnerWithDataSource()
                .withPropertyValues(
                        "management.endpoints.web.exposure.include=hikari",
                        "mini-shop.actuator-extras.enabled=false"
                )
                .run(ctx -> assertThat(ctx).doesNotHaveBean(HikariEndpoint.class));
    }

    /**
     * DataSource 가 하나도 없는 컨텍스트 — endpoint 는 여전히 등록되고, 호출하면 빈 pools 를
     * 돌려줄 뿐 부팅을 깨지 않는다 (List<DataSource> 가 빈 리스트로 주입).
     */
    @Test
    void registersEndpointEvenWithoutDataSource() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        EndpointAutoConfiguration.class,
                        WebEndpointAutoConfiguration.class,
                        ActuatorExtrasAutoConfiguration.class
                ))
                .withPropertyValues("management.endpoints.web.exposure.include=hikari")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(HikariEndpoint.class);
                    Object body = ctx.getBean(HikariEndpoint.class).hikari().get("pools");
                    assertThat((java.util.List<?>) body).isEmpty();
                });
    }

    /**
     * 회귀 방지 — HikariEndpoint 가 actuator endpoint 로 정상 인식되는지. {@code @Endpoint(id="hikari")}
     * 가 빠지거나 actuator 클래스패스가 깨지면 endpoint id 로 조회가 안 된다.
     */
    @Test
    void endpointIsRegisteredUnderHikariId() {
        runnerWithDataSource()
                .withPropertyValues("management.endpoints.web.exposure.include=hikari")
                .run(ctx -> {
                    HikariEndpoint endpoint = ctx.getBean(HikariEndpoint.class);
                    // H2 DataSource 는 HikariDataSource 이므로, 풀이 부팅된 뒤 (첫 커넥션 후)
                    // 스냅샷이 보인다. 여기서는 등록 여부만 확인 — 스냅샷 내용은 HikariEndpointTests.
                    assertThat(endpoint).isNotNull();
                    assertThat(ctx.getBean(javax.sql.DataSource.class)).isInstanceOf(HikariDataSource.class);
                });
    }
}
