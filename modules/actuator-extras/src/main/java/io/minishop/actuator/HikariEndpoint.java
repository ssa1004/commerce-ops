package io.minishop.actuator;

import com.zaxxer.hikari.HikariConfigMXBean;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /actuator/hikari} — Spring 컨텍스트의 모든 HikariCP 풀에 대해 active / idle /
 * pending / 설정값 스냅샷을 돌려주는 read-only actuator endpoint.
 *
 * <h2>왜 메트릭이 있는데 endpoint 인가</h2>
 * <p>{@code hikaricp_connections_pending} 같은 메트릭은 Prometheus 스크랩 주기 (15~60s) 의
 * 표본이라, "방금 5초간 pending 이 치솟았다" 같은 짧은 사건을 자주 놓친다. 운영 중
 * "지금 이 순간" 풀 상태를 콘솔에서 즉시 보고 싶을 때가 있다 — 이 endpoint 가 그 자리.
 *
 * <h2>읽기 경로</h2>
 * <ul>
 *   <li>{@link HikariDataSource#getHikariPoolMXBean()} — active / idle / total / pending 라이브 값.</li>
 *   <li>{@link HikariDataSource#getHikariConfigMXBean()} — maxPoolSize / minIdle 설정값
 *       (pending 을 해석하는 기준선).</li>
 * </ul>
 *
 * <h2>경계 케이스</h2>
 * <ul>
 *   <li><b>다중 DataSource</b> — 여러 풀이 있으면 풀 이름별로 모두 나열한다.</li>
 *   <li><b>Hikari 가 아닌 DataSource</b> — {@code instanceof HikariDataSource} 가 아니면
 *       조용히 건너뛴다 (예: 테스트용 다른 풀, 프록시 래퍼).</li>
 *   <li><b>풀이 아직 부팅 안 됨</b> — Hikari 는 첫 커넥션 요청 전까지 PoolMXBean 이 null 일 수
 *       있다. 그 경우 해당 풀은 결과에서 제외 — NPE 대신 "아직 안 보임" 으로 처리.</li>
 * </ul>
 */
@Endpoint(id = "hikari")
public class HikariEndpoint {

    private static final Logger log = LoggerFactory.getLogger(HikariEndpoint.class);

    private final List<DataSource> dataSources;

    public HikariEndpoint(List<DataSource> dataSources) {
        this.dataSources = dataSources;
    }

    /**
     * 모든 HikariCP 풀의 스냅샷.
     *
     * @return {@code pools} 키 아래 풀별 {@link HikariPoolSnapshot} 리스트. Hikari 풀이 하나도
     *         없으면 빈 리스트.
     */
    @ReadOperation
    public Map<String, Object> hikari() {
        List<HikariPoolSnapshot> pools = new ArrayList<>();
        for (DataSource ds : dataSources) {
            HikariPoolSnapshot snapshot = snapshotOf(ds);
            if (snapshot != null) {
                pools.add(snapshot);
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pools", pools);
        return body;
    }

    /**
     * 한 DataSource 가 HikariCP 풀이고 이미 부팅됐으면 스냅샷을, 아니면 {@code null}.
     */
    private HikariPoolSnapshot snapshotOf(DataSource ds) {
        if (!(ds instanceof HikariDataSource hikari)) {
            return null;
        }
        HikariPoolMXBean pool = hikari.getHikariPoolMXBean();
        if (pool == null) {
            // 풀이 아직 첫 커넥션을 만들기 전 — MXBean 이 노출되지 않은 상태. 다음 호출 때 보인다.
            log.debug("HikariCP pool '{}' not initialized yet, skipping snapshot", hikari.getPoolName());
            return null;
        }
        HikariConfigMXBean config = hikari.getHikariConfigMXBean();
        return new HikariPoolSnapshot(
                hikari.getPoolName(),
                pool.getActiveConnections(),
                pool.getIdleConnections(),
                pool.getTotalConnections(),
                pool.getThreadsAwaitingConnection(),
                config.getMaximumPoolSize(),
                config.getMinimumIdle()
        );
    }
}
