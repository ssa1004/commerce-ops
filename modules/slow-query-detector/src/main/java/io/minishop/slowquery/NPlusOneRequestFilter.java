package io.minishop.slowquery;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 요청 단위로 N+1 추적 윈도우를 닫는다.
 *
 * <p>왜 필요한가:
 * <ol>
 *   <li>{@link NPlusOneContext} 의 카운트는 ThreadLocal 에 들어간다.</li>
 *   <li>트랜잭션이 있으면 종료 시 afterCompletion 콜백이 알아서 정리.</li>
 *   <li>트랜잭션이 *없는* 경로에서 쿼리가 나오면 (예: {@code @Transactional} 없는 컨트롤러의 직접 JdbcTemplate
 *       호출, READ_ONLY 트랜잭션 종료 후의 lazy load, actuator/static 경로) ThreadLocal 이 비워지지 않는다.</li>
 *   <li>servlet 컨테이너는 worker 스레드를 재사용하므로, 다음 요청이 같은 스레드를 받으면
 *       이전 요청의 카운트가 그대로 이어진다 → "이전 + 이번" 합이 임계를 넘어 가짜 N+1 경고.</li>
 * </ol>
 *
 * <p>이 필터는 매 요청 끝에 한 번 더 {@link NPlusOneContext#reset()} 을 호출해 그 누수를 막는다.
 */
public class NPlusOneRequestFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            filterChain.doFilter(request, response);
        } finally {
            // 트랜잭션이 정상 처리된 경우엔 이미 비어 있어 no-op 에 가까운 비용.
            // 트랜잭션 밖 쿼리가 있었던 경우에만 실제 의미가 있다.
            NPlusOneContext.reset();
        }
    }
}
