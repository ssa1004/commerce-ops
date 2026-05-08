package io.minishop.slowquery;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class NPlusOneRequestFilterTests {

	/**
	 * 트랜잭션 밖에서 실행된 쿼리가 ThreadLocal 에 남았다면, 다음 요청에서 같은 쓰레드 (worker thread
	 * 재사용) 가 사용될 때 이전 요청의 카운트가 누적되어 가짜 N+1 임계가 발동할 수 있다.
	 * 필터가 요청 끝에 reset() 을 보장해야 한다.
	 */
	@Test
	void resetsContextAfterRequest() throws Exception {
		try {
			NPlusOneContext.observe("select * from a where id = ?");
			assertThat(NPlusOneContext.observe("select * from a where id = ?")).isEqualTo(2);

			NPlusOneRequestFilter filter = new NPlusOneRequestFilter();
			HttpServletRequest req = new MockHttpServletRequest();
			HttpServletResponse res = new MockHttpServletResponse();
			filter.doFilter(req, res, new MockFilterChain());

			// 다음 호출은 0 부터 다시 시작 (즉 첫 번째 observe 가 1 을 돌려준다).
			assertThat(NPlusOneContext.observe("select * from a where id = ?")).isEqualTo(1);
		} finally {
			NPlusOneContext.reset();
		}
	}

	/**
	 * 체인이 예외를 던져도 finally 에서 reset 이 호출되어야 한다 (요청 처리 중 예외 발생 시 누수 방지).
	 */
	@Test
	void resetsContextEvenIfChainThrows() {
		try {
			NPlusOneContext.observe("select 1");

			NPlusOneRequestFilter filter = new NPlusOneRequestFilter();
			MockFilterChain chain = new MockFilterChain() {
				@Override
				public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
					throw new RuntimeException("boom");
				}
			};
			try {
				filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);
			} catch (Exception expected) {
				// 컨테이너로 전파될 RuntimeException — 무시
			}
			assertThat(NPlusOneContext.observe("select 1")).isEqualTo(1);
		} finally {
			NPlusOneContext.reset();
		}
	}
}
