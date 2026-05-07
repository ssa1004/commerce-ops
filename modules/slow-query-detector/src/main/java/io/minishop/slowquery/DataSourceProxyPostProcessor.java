package io.minishop.slowquery;

import net.ttddyy.dsproxy.support.ProxyDataSource;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;

import javax.sql.DataSource;

/**
 * Spring 컨텍스트의 모든 DataSource bean 을 ProxyDataSource (호출을 가로채 측정 훅을 끼워주는
 * 래퍼) 로 감싼다. 사용자 코드는 변경할 필요 없이, 의존성만 추가하면 자동 적용.
 */
class DataSourceProxyPostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(DataSourceProxyPostProcessor.class);

    private final SlowQueryListener listener;

    DataSourceProxyPostProcessor(SlowQueryListener listener) {
        this.listener = listener;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (!(bean instanceof DataSource ds) || bean instanceof ProxyDataSource) {
            return bean;
        }
        log.debug("slow-query-detector wrapping DataSource bean '{}'", beanName);
        return ProxyDataSourceBuilder.create(ds)
                .name("slow-query-detector")
                .listener(listener)
                .build();
    }
}
