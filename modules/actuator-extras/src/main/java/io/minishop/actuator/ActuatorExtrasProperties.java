package io.minishop.actuator;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * actuator-extras 의 ConfigurationProperties.
 *
 * <p>v0.1 범위:
 * <ul>
 *   <li>HikariCP 커넥션 풀 스냅샷 endpoint ({@code /actuator/hikari}) 만 제공.</li>
 *   <li>ThreadPool / Transaction / Dependency ping endpoint 는 후속 단계 — ROADMAP
 *       Phase 3 Step 8 참조.</li>
 * </ul>
 *
 * <p>endpoint 자체의 노출 (web exposure) / 보안 가드는 본 모듈이 아니라 사용자 앱의
 * {@code management.endpoints.web.exposure.include} 와 Spring Security 설정 책임이다 —
 * 운영 환경에서 풀 내부 상태가 인증 없이 새지 않도록.
 *
 * @param enabled 모듈 활성 여부. 기본 true. {@code false} 면 endpoint bean 이 등록되지 않는다.
 */
@ConfigurationProperties(prefix = "mini-shop.actuator-extras")
public record ActuatorExtrasProperties(
        boolean enabled
) {
    public ActuatorExtrasProperties {
        // record 의 canonical constructor — 현재는 정규화할 필드가 없지만, 후속 단계에서
        // transactions.slow-threshold 등이 추가되면 여기서 기본값을 채운다 (다른 모듈의
        // properties record 와 같은 패턴).
    }
}
