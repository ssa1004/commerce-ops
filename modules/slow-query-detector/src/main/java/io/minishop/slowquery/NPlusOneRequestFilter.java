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
 * 트랜잭션이 활성화된 경우엔 {@link NPlusOneContext} 가 afterCompletion 콜백으로 알아서 정리한다.
 * 그러나 트랜잭션 밖에서 (예: {@code @Transactional} 없는 컨트롤러가 JdbcTemplate 으로 직접 쿼리,
 * READ_ONLY 트랜잭션 후의 lazy load, 단순 actuator/static 경로) 쿼리가 나오는 경우에는 ThreadLocal
 * 이 그대로 남는다. servlet 컨테이너의 worker 스레드가 다음 요청에서 재사용되면 이전 요청의 카운트가
 * 그대로 누적돼 *이전 요청 + 이번 요청 합산* 이 임계를 넘어 가짜 N+1 경고가 발생할 수 있다.
 *
 * 이 필터는 요청 처리 끝에 한 번 더 reset() 을 호출해 그런 누수를 차단한다.
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
