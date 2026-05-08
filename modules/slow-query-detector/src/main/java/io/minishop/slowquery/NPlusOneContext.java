package io.minishop.slowquery;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.HashMap;
import java.util.Map;

/**
 * 스레드별 N+1 추적 윈도우 — 이 스레드가 처리 중인 작업 동안의 "SQL 모양 → 횟수" 카운트.
 *
 * <p>같은 정규화 SQL (리터럴을 `?` 로 치환해 모양만 비교) 이 한 윈도우 안에서 반복되면 카운트가 올라간다.
 *
 * <p>윈도우의 끝 (= 카운트 초기화 시점) 은:
 * <ul>
 *   <li>Spring 트랜잭션이 있으면 → 그 트랜잭션 종료 시 자동 (afterCompletion 콜백 등록).</li>
 *   <li>트랜잭션이 없으면 → 호출자가 {@link #reset()} 을 부르지 않는 한 그대로 남는다.
 *       서블릿 환경에서는 {@link NPlusOneRequestFilter} 가 매 요청 끝에 reset 을 호출해 누수를 막는다.</li>
 * </ul>
 *
 * <p>왜 트랜잭션 밖 쿼리는 자동 정리를 하지 않나: N+1 은 거의 항상 트랜잭션 안에서 발생한다 (JPA lazy
 * loading 등). 트랜잭션 밖 쿼리에 휴리스틱이 다소 부정확해도 실용적 손해는 작다 — 정확도보다 단순함을 택했다.
 */
final class NPlusOneContext {

    private static final ThreadLocal<Map<String, Integer>> COUNTS = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> CLEANUP_REGISTERED = new ThreadLocal<>();

    private NPlusOneContext() {}

    /**
     * 카운트를 증가시키고 누적 카운트를 돌려준다.
     */
    static int observe(String normalizedSql) {
        Map<String, Integer> map = COUNTS.get();
        if (map == null) {
            map = new HashMap<>();
            COUNTS.set(map);
            registerCleanupIfInTx();
        }
        return map.merge(normalizedSql, 1, Integer::sum);
    }

    static void reset() {
        COUNTS.remove();
        CLEANUP_REGISTERED.remove();
    }

    private static void registerCleanupIfInTx() {
        if (Boolean.TRUE.equals(CLEANUP_REGISTERED.get())) return;
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return;

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                reset();
            }
        });
        CLEANUP_REGISTERED.set(Boolean.TRUE);
    }
}
