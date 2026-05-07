package io.minishop.slowquery;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.listener.QueryExecutionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * datasource-proxy의 모든 쿼리 실행 직후 호출된다.
 *  - 임계치 초과 → slow_query_total 카운터 증가 + WARN 로그 (옵션: stack trace 일부)
 *  - 동일 정규화 SQL 반복 → n_plus_one_total 카운터 증가 (임계 도달 순간 한 번)
 *  - 모든 쿼리는 query_execution_seconds 타이머로 분포 측정
 */
public class SlowQueryListener implements QueryExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(SlowQueryListener.class);

    private final SlowQueryDetectorProperties props;
    private final MeterRegistry meterRegistry;

    private final Counter slowQueryCounter;
    private final Counter nPlusOneCounter;

    public SlowQueryListener(SlowQueryDetectorProperties props, MeterRegistry meterRegistry) {
        this.props = props;
        this.meterRegistry = meterRegistry;
        this.slowQueryCounter = meterRegistry.counter("slow_query_total");
        this.nPlusOneCounter = meterRegistry.counter("n_plus_one_total");
    }

    @Override
    public void beforeQuery(ExecutionInfo execInfo, List<QueryInfo> queryInfoList) {
        // datasource-proxy가 elapsed time을 자동 계산하니 별도 시작 시각 기록 불필요.
    }

    @Override
    public void afterQuery(ExecutionInfo execInfo, List<QueryInfo> queryInfoList) {
        long elapsedMs = execInfo.getElapsedTime();
        boolean slow = elapsedMs > props.slowThreshold().toMillis();

        for (QueryInfo q : queryInfoList) {
            String sql = q.getQuery();
            String normalized = SqlNormalizer.normalize(sql);

            recordTimer(elapsedMs, slow);

            if (slow) {
                slowQueryCounter.increment();
                logSlow(sql, elapsedMs);
            }

            int count = NPlusOneContext.observe(normalized);
            if (count == props.nPlusOneThreshold()) {
                // 임계 도달 *시점에만* 한 번 카운트. 이후 같은 패턴 반복은 이미 N+1로 알고 있음.
                nPlusOneCounter.increment();
                logNPlusOne(normalized, count);
            }
        }
    }

    private void recordTimer(long elapsedMs, boolean slow) {
        Timer.builder("query_execution_seconds")
                .tag("outcome", slow ? "slow" : "ok")
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(elapsedMs, TimeUnit.MILLISECONDS);
    }

    private void logSlow(String sql, long elapsedMs) {
        if (props.captureStacktrace()) {
            log.warn("Slow query {} ms: {}\n{}", elapsedMs, sql, callerStack());
        } else {
            log.warn("Slow query {} ms: {}", elapsedMs, sql);
        }
    }

    private void logNPlusOne(String normalizedSql, int count) {
        if (props.captureStacktrace()) {
            log.warn("Suspected N+1 ({} executions): {}\n{}", count, normalizedSql, callerStack());
        } else {
            log.warn("Suspected N+1 ({} executions): {}", count, normalizedSql);
        }
    }

    /**
     * datasource-proxy / spring / hibernate 프레임워크 프레임을 거른 사용자 스택 일부만 잘라낸다.
     */
    private String callerStack() {
        StackTraceElement[] full = Thread.currentThread().getStackTrace();
        StringBuilder sb = new StringBuilder();
        int kept = 0;
        for (StackTraceElement e : full) {
            String cls = e.getClassName();
            if (cls.startsWith("java.")
                    || cls.startsWith("jdk.")
                    || cls.startsWith("net.ttddyy")
                    || cls.startsWith("io.minishop.slowquery")
                    || cls.startsWith("org.springframework")
                    || cls.startsWith("org.hibernate")
                    || cls.startsWith("com.zaxxer.hikari")) {
                continue;
            }
            sb.append("    at ").append(e).append('\n');
            if (++kept >= props.stacktraceDepth()) break;
        }
        return sb.toString();
    }

    // 테스트 헬퍼
    Counter slowQueryCounter() { return slowQueryCounter; }
    Counter nPlusOneCounter() { return nPlusOneCounter; }
}
