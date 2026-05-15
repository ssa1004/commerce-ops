package io.minishop.actuator;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link HikariEndpoint} 의 스냅샷 로직 검증 — 실제 {@link HikariDataSource} (H2 인메모리) 를
 * 띄워 active / idle / pending 이 풀의 라이브 상태를 반영하는지 본다.
 */
class HikariEndpointTests {

    private final List<HikariDataSource> opened = new ArrayList<>();

    @AfterEach
    void closePools() {
        opened.forEach(HikariDataSource::close);
        opened.clear();
    }

    private HikariDataSource newPool(String poolName, int maxPoolSize, int minIdle) {
        HikariConfig config = new HikariConfig();
        config.setPoolName(poolName);
        config.setJdbcUrl("jdbc:h2:mem:" + poolName + ";DB_CLOSE_DELAY=-1");
        config.setDriverClassName("org.h2.Driver");
        config.setMaximumPoolSize(maxPoolSize);
        config.setMinimumIdle(minIdle);
        HikariDataSource ds = new HikariDataSource(config);
        opened.add(ds);
        return ds;
    }

    @Test
    @SuppressWarnings("unchecked")
    void reportsPoolConfigAndLiveCounts() throws Exception {
        HikariDataSource ds = newPool("primary", 5, 1);
        // 풀을 부팅시키기 위해 커넥션을 한 번 받았다 반납 — 이후 idle 로 남는다.
        try (Connection c = ds.getConnection()) {
            assertThat(c).isNotNull();
        }

        HikariEndpoint endpoint = new HikariEndpoint(List.of(ds));
        List<HikariPoolSnapshot> pools = (List<HikariPoolSnapshot>) endpoint.hikari().get("pools");

        assertThat(pools).hasSize(1);
        HikariPoolSnapshot snapshot = pools.get(0);
        assertThat(snapshot.pool()).isEqualTo("primary");
        assertThat(snapshot.maxPoolSize()).isEqualTo(5);
        assertThat(snapshot.minIdle()).isEqualTo(1);
        // 커넥션을 반납했으므로 active 0, 풀에 최소 1개는 idle 로 남아 있다.
        assertThat(snapshot.active()).isZero();
        assertThat(snapshot.idle()).isGreaterThanOrEqualTo(1);
        assertThat(snapshot.total()).isEqualTo(snapshot.active() + snapshot.idle());
        // 대기열 — 한가한 풀이므로 0.
        assertThat(snapshot.pending()).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void reflectsActiveConnectionsWhileBorrowed() throws Exception {
        HikariDataSource ds = newPool("borrow", 5, 0);
        HikariEndpoint endpoint = new HikariEndpoint(List.of(ds));

        try (Connection borrowed = ds.getConnection()) {
            assertThat(borrowed).isNotNull();
            // 커넥션을 들고 있는 동안 스냅샷 — active 가 1 로 보여야 한다.
            List<HikariPoolSnapshot> pools = (List<HikariPoolSnapshot>) endpoint.hikari().get("pools");
            assertThat(pools).hasSize(1);
            assertThat(pools.get(0).active()).isEqualTo(1);
        }

        // 반납 후 — active 0 으로 돌아온다.
        List<HikariPoolSnapshot> afterReturn = (List<HikariPoolSnapshot>) endpoint.hikari().get("pools");
        assertThat(afterReturn.get(0).active()).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void listsMultiplePools() throws Exception {
        HikariDataSource a = newPool("pool-a", 3, 0);
        HikariDataSource b = newPool("pool-b", 7, 2);
        // 둘 다 부팅.
        try (Connection ca = a.getConnection(); Connection cb = b.getConnection()) {
            assertThat(ca).isNotNull();
            assertThat(cb).isNotNull();
        }

        HikariEndpoint endpoint = new HikariEndpoint(List.of(a, b));
        List<HikariPoolSnapshot> pools = (List<HikariPoolSnapshot>) endpoint.hikari().get("pools");

        assertThat(pools).extracting(HikariPoolSnapshot::pool)
                .containsExactlyInAnyOrder("pool-a", "pool-b");
        assertThat(pools).extracting(HikariPoolSnapshot::maxPoolSize)
                .containsExactlyInAnyOrder(3, 7);
    }

    /**
     * Hikari 가 아닌 DataSource 는 조용히 건너뛴다 — NPE / ClassCastException 없이.
     */
    @Test
    @SuppressWarnings("unchecked")
    void skipsNonHikariDataSource() {
        DataSource notHikari = new org.springframework.jdbc.datasource.SimpleDriverDataSource();
        HikariEndpoint endpoint = new HikariEndpoint(List.of(notHikari));
        List<HikariPoolSnapshot> pools = (List<HikariPoolSnapshot>) endpoint.hikari().get("pools");
        assertThat(pools).isEmpty();
    }

    /**
     * DataSource 가 하나도 없으면 빈 pools — 호출 자체는 안전.
     */
    @Test
    @SuppressWarnings("unchecked")
    void emptyWhenNoDataSources() {
        HikariEndpoint endpoint = new HikariEndpoint(List.of());
        List<HikariPoolSnapshot> pools = (List<HikariPoolSnapshot>) endpoint.hikari().get("pools");
        assertThat(pools).isEmpty();
    }
}
