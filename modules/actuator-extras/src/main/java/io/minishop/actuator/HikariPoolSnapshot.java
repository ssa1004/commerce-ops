package io.minishop.actuator;

/**
 * 한 HikariCP 풀의 *지금 이 순간* 스냅샷 — {@link HikariEndpoint} 응답의 풀별 항목.
 *
 * <p>active / idle / total / pending 은 {@code HikariPoolMXBean} 이 노출하는 라이브 값이다.
 * 메트릭 ({@code hikaricp_connections_*}) 으로도 같은 수치를 볼 수 있지만, 메트릭은 스크랩
 * 주기 (보통 15~60s) 의 표본이라 *방금 막힌* 상황을 놓치기 쉽다. 이 endpoint 는 호출 시점의
 * 값을 그대로 돌려줘 "지금 pending 이 쌓였나" 를 즉시 확인하는 용도.
 *
 * @param pool       풀 이름 (HikariConfig.poolName — 보통 DataSource bean 이름 기반).
 * @param active     사용 중인 커넥션 수 (빌려간 뒤 아직 반납 안 됨).
 * @param idle       놀고 있는 커넥션 수 (풀에 대기 중, 바로 빌려줄 수 있음).
 * @param total      active + idle — 현재 풀이 들고 있는 물리 커넥션 총수.
 * @param pending    커넥션을 받으려고 줄 서서 대기 중인 스레드 수. 0 이 정상, &gt;0 이 지속되면
 *                   풀 크기 부족 또는 커넥션 누수 의심.
 * @param maxPoolSize 설정상 최대 풀 크기 (HikariConfig.maximumPoolSize) — pending 해석의 기준선.
 * @param minIdle    설정상 최소 idle 유지 수 (HikariConfig.minimumIdle).
 */
public record HikariPoolSnapshot(
        String pool,
        int active,
        int idle,
        int total,
        int pending,
        int maxPoolSize,
        int minIdle
) {
}
