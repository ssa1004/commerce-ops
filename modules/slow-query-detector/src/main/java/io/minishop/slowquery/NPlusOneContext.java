package io.minishop.slowquery;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-thread N+1 추적 윈도우.
 *
 * 동일 normalized SQL이 같은 윈도우에서 반복되면 카운트가 올라간다.
 * 윈도우 경계:
 *  - Spring 트랜잭션이 활성화되어 있으면 → 그 트랜잭션 종료 시 자동 정리 (afterCompletion)
 *  - 트랜잭션이 없으면 → 호출자가 명시적으로 reset() 하지 않는 한 ThreadLocal에 누적
 *    (서블릿 환경에서 worker thread 재사용으로 leak 가능 — DESIGN.md에 한계 명시)
 *
 * 이 한계를 지금 수용하는 이유: N+1은 거의 항상 트랜잭션 안에서 발생 (JPA lazy loading 등).
 * 트랜잭션 밖 쿼리에는 N+1 휴리스틱이 부정확해도 큰 손해가 없다.
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
