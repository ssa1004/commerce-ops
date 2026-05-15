package io.minishop.actuator;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;
import java.util.List;

/**
 * 의존성을 추가하면 Spring Boot 가 자동으로 이 설정을 적용한다.
 * {@code mini-shop.actuator-extras.enabled=false} 로 끌 수 있다.
 *
 * <h2>활성 조건</h2>
 * <ul>
 *   <li>{@code HikariDataSource} / actuator {@code @Endpoint} 클래스가 클래스패스에 있어야 함 —
 *       없는 환경 (다른 커넥션 풀, actuator 미포함 슬림 컨텍스트) 에서는 자동설정 자체가
 *       평가되지 않아 NoClassDefFoundError 가 안 난다.</li>
 *   <li>{@code @ConditionalOnAvailableEndpoint} — 사용자가 {@code management.endpoints.web.exposure.include}
 *       에 {@code hikari} 를 넣어 노출을 켰을 때만 endpoint bean 을 만든다. 노출하지 않을
 *       거면 bean 도 만들지 않아 불필요한 wiring 을 줄인다.</li>
 * </ul>
 *
 * <h2>순서</h2>
 * <p>{@code after = DataSourceAutoConfiguration.class} — DataSource bean 이 먼저 등록된 뒤에
 * endpoint 가 그것을 주입받도록. DataSource 가 하나도 없으면 {@code List<DataSource>} 는
 * 빈 리스트로 주입되고, endpoint 는 빈 {@code pools} 를 돌려줄 뿐 부팅을 깨지 않는다.
 */
@AutoConfiguration(after = DataSourceAutoConfiguration.class)
@ConditionalOnClass({HikariDataSource.class, Endpoint.class})
@ConditionalOnProperty(prefix = "mini-shop.actuator-extras", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ActuatorExtrasProperties.class)
public class ActuatorExtrasAutoConfiguration {

    /**
     * {@code /actuator/hikari} endpoint.
     *
     * <p>{@code ObjectProvider<DataSource>} 로 받아 0..N 개를 모두 모은다 — 다중 DataSource
     * 환경에서도 풀별로 나열할 수 있도록. DataSource 가 없는 컨텍스트에서도 안전 (빈 스트림).
     *
     * <p>{@code @ConditionalOnMissingBean} — 사용자가 같은 endpoint 를 직접 정의했다면 그쪽을
     * 존중하고 물러난다 (다른 모듈들과 같은 정책).
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnAvailableEndpoint(endpoint = HikariEndpoint.class)
    public HikariEndpoint hikariEndpoint(ObjectProvider<DataSource> dataSources) {
        List<DataSource> all = dataSources.orderedStream().toList();
        return new HikariEndpoint(all);
    }
}
