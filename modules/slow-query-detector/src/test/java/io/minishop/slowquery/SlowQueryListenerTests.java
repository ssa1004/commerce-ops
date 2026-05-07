package io.minishop.slowquery;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SlowQueryListenerTests {

	MeterRegistry registry;
	SlowQueryListener listener;

	@BeforeEach
	void setUp() {
		registry = new SimpleMeterRegistry();
		listener = new SlowQueryListener(
				new SlowQueryDetectorProperties(true, Duration.ofMillis(100), 3, false, 8),
				registry
		);
		// 테스트 간 ThreadLocal 상태 격리
		NPlusOneContext.reset();
	}

	@Test
	void underThresholdDoesNotIncrementSlowCounter() {
		fire("SELECT 1", 50);
		assertThat(registry.counter("slow_query_total").count()).isZero();
	}

	@Test
	void overThresholdIncrementsSlowCounter() {
		fire("SELECT * FROM big_table", 250);
		assertThat(registry.counter("slow_query_total").count()).isEqualTo(1.0);
	}

	@Test
	void repeatedSqlIncrementsNPlusOneOnceAtThreshold() {
		// 임계 = 3. 같은 패턴 5번 실행 → 카운터는 한 번만 증가 (임계 도달 순간에만 카운트)
		for (int i = 0; i < 5; i++) {
			fire("SELECT * FROM orders WHERE id = " + i, 5);
		}
		assertThat(registry.counter("n_plus_one_total").count()).isEqualTo(1.0);
	}

	@Test
	void differentSqlPatternsAreTrackedSeparately() {
		fire("SELECT * FROM a WHERE id = 1", 5);
		fire("SELECT * FROM a WHERE id = 2", 5);
		fire("SELECT * FROM b WHERE id = 1", 5);
		// a 패턴 2회, b 패턴 1회 — 임계 3 미만이므로 카운터 0
		assertThat(registry.counter("n_plus_one_total").count()).isZero();
	}

	@Test
	void timerCapturesEveryQuery() {
		fire("SELECT 1", 5);
		fire("SELECT 1", 5);
		fire("SELECT * FROM big_table", 250);
		assertThat(registry.find("query_execution_seconds").timers())
				.allSatisfy(t -> assertThat(t.count()).isPositive());
		assertThat(registry.find("query_execution_seconds").tag("outcome", "ok").timer().count())
				.isEqualTo(2);
		assertThat(registry.find("query_execution_seconds").tag("outcome", "slow").timer().count())
				.isEqualTo(1);
	}

	private void fire(String sql, long elapsedMs) {
		ExecutionInfo execInfo = new ExecutionInfo();
		execInfo.setElapsedTime(elapsedMs);
		QueryInfo q = new QueryInfo();
		q.setQuery(sql);
		listener.afterQuery(execInfo, List.of(q));
	}
}
