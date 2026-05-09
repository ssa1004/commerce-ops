package io.minishop.slowquery;

import net.ttddyy.dsproxy.support.ProxyDataSource;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;

import javax.sql.DataSource;

/**
 * Spring 컨텍스트의 모든 DataSource bean 을 ProxyDataSource (호출을 가로채 측정 훅을 끼워주는
 * 래퍼) 로 감싼다. 사용자 코드는 변경할 필요 없이, 의존성만 추가하면 자동 적용.
 *
 * <h2>왜 {@link SlowQueryListener} 를 lazy ObjectProvider 로 받나</h2>
 * <p>BeanPostProcessor 는 컨텍스트 부팅 *극초기* 에 등록된다 — 다른 일반 bean 보다 먼저. 만약
 * 생성자에서 리스너를 *직접* (즉 {@code SlowQueryListener listener}) 받으면 Spring 은 이 BPP 를
 * 만들기 위해 리스너 → MeterRegistry 까지 즉시 만들어버린다. 그러면:
 * <ol>
 *   <li>Spring 콘솔에 {@code "Bean 'prometheusMeterRegistry' is not eligible for getting processed
 *       by all BeanPostProcessors. Is this bean getting eagerly injected/applied to a currently
 *       created BeanPostProcessor [dataSourceProxyPostProcessor]?"} 경고가 찍힌다.</li>
 *   <li>그보다 더 심각한 부수효과: {@code MeterRegistryPostProcessor} 가 아직 wiring 안 된 시점이라
 *       JvmMemoryMetrics / JvmGcMetrics / ProcessorMetrics 같은 *MeterBinder 들이 이 레지스트리에
 *       바인딩되지 못한다*. 결과적으로 {@code /actuator/prometheus} 에서 {@code jvm_memory_used_bytes}
 *       전체 계열이 사라진다 — 모니터링/알람의 핵심 메트릭이 통째로 누락.</li>
 * </ol>
 * <p>{@link ObjectProvider#getObject()} 는 *호출되는 시점에* lookup 한다 — DataSource bean 이 만들어진
 * 직후에야 우리 BPP 의 {@code postProcessAfterInitialization} 가 호출되므로, 그 시점이면 MeterRegistry
 * 와 모든 MeterBinder 가 이미 bind 완료된 상태다.
 *
 * <p>또 한 가지 효과: 사용자 앱이 실수로 DataSource bean 을 정의하지 않은 슬림 컨텍스트에서도
 * 리스너 자체가 평가되지 않아 안전.
 */
class DataSourceProxyPostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(DataSourceProxyPostProcessor.class);

    private final ObjectProvider<SlowQueryListener> listenerProvider;

    DataSourceProxyPostProcessor(ObjectProvider<SlowQueryListener> listenerProvider) {
        this.listenerProvider = listenerProvider;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (!(bean instanceof DataSource ds) || bean instanceof ProxyDataSource) {
            return bean;
        }
        log.debug("slow-query-detector wrapping DataSource bean '{}'", beanName);
        return ProxyDataSourceBuilder.create(ds)
                .name("slow-query-detector")
                .listener(listenerProvider.getObject())
                .build();
    }
}
