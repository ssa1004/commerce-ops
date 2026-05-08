package io.minishop.order.concurrency;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.lang.NonNull;

import java.io.IOException;

/**
 * RestClient 의 {@link ClientHttpRequestInterceptor} — 매 외부 호출을 {@link AdaptiveLimiter} 안에서
 * 실행. 한도 초과면 {@link LimitExceededException} 즉시 (요청은 발사되지 않는다 — backend 부하
 * 차단의 핵심).
 *
 * <p>왜 RestClient interceptor 위치에 두는가:
 * <ul>
 *   <li>connect/read timeout 과 같은 layer — limiter 와 timeout 이 같은 자리에서 *동시* 동작.</li>
 *   <li>upstream 이 5xx/timeout 으로 실패해도 latency (RTT) 는 algorithm 에 그대로 반영 — 느려진
 *       backend 를 빠르게 감지.</li>
 *   <li>다른 layer (예: 호출 메서드 단위) 에 두면 같은 클라이언트가 여러 endpoint 를 부를 때
 *       혼합되어 신호가 섞인다. *upstream service 단위* 가 적절한 격리 단위.</li>
 * </ul>
 */
public class AdaptiveLimiterInterceptor implements ClientHttpRequestInterceptor {

    private final AdaptiveLimiter limiter;

    public AdaptiveLimiterInterceptor(AdaptiveLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    @NonNull
    public ClientHttpResponse intercept(@NonNull HttpRequest request,
                                        @NonNull byte[] body,
                                        @NonNull ClientHttpRequestExecution execution) throws IOException {
        AdaptiveLimiter.Listener l = limiter.acquire();
        try {
            ClientHttpResponse response = execution.execute(request, body);
            int status = response.getStatusCode().value();
            // 5xx 는 backend 부담의 신호 — onSuccess 로 latency 만 반영하면 RTT 는 짧아도 backend
            // 가 망가지고 있다는 정보가 유실. onDropped 로 알림 = "backend 가 이 요청을 처리 못 함".
            //   - Gradient2 가 onDropped 를 받으면 limit 을 더 공격적으로 줄임.
            // 4xx 는 비즈니스 결과 (404/409/402) — backend 부담과 무관. onSuccess.
            if (status >= 500) {
                l.onDropped();
            } else {
                l.onSuccess();
            }
            return response;
        } catch (IOException e) {
            // socket / connection 실패 — onDropped (backend 가 응답 자체를 못 줌)
            l.onDropped();
            throw e;
        } catch (RuntimeException e) {
            // 미상 — 알고리즘에 신호로 쓰지 말 것. 우리쪽 코드 / serialization 오류일 수 있음.
            l.onIgnore();
            throw e;
        }
    }
}
