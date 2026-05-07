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
 * datasource-proxy 가 모든 쿼리 실행 직후 이 리스너를 호출한다.
 *  - 임계치 초과 → slow_query_total 카운터 증가 + WARN 로그 (옵션: 호출 스택 일부)
 *  - 동일 정규화 SQL (리터럴을 `?` 로 치환해 모양만 비교) 반복 → n_plus_one_total 카운터 증가
 *    (임계 도달 순간 한 번만 — 같은 N+1 이 1000번 실행돼도 카운트는 1)
 *  - 모든 쿼리는 query_execution_seconds 타이머로 분포 (p50/p95/p99 등) 측정
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
        // datasource-proxy 가 실행 시간 (elapsed time) 을 자동 계산해 주므로 별도 시작 시각 기록 불필요.
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
                // 임계 도달 *시점에만* 한 번 카운트 (`==` 임에 주의).
                // 이후 같은 패턴 반복은 이미 N+1 로 알고 있어 카운트하지 않음 → 메트릭은
                // *고유 N+1 사례 발생 빈도* 가 됨.
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
     * datasource-proxy / Spring / Hibernate / Hikari 프레임워크 프레임을 거른 사용자 코드 스택만
     * N (기본 8) 줄 잘라낸다. "어떤 컨트롤러/서비스/리포지토리가 N+1 을 만들었나" 를 바로 식별하기 위함.
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
