package io.minishop.order.retry;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * exponential backoff + jitter 계산. {@link RetryInterceptor} 가 매 attempt 의 wait 시간을 산정할 때
 * 사용. 단위 테스트를 위해 random 공급자 (DoubleSupplier) 를 주입 가능.
 *
 * <p>설계 의도:
 * <ul>
 *   <li><b>thundering herd 차단</b> — backend 가 일시 503 으로 다수 호출자 retry 를 동시에 받으면,
 *       jitter 없는 순수 exponential 은 *모두 같은 시점에* 다시 시도해 같은 사고를 반복. jitter 가
 *       시간을 분산.</li>
 *   <li><b>cap</b> — multiplier 가 attempt 마다 곱해지면 빠르게 큰 값이 됨. maxDelay 캡으로 retry
 *       전체 시간이 폭주하지 않게.</li>
 *   <li><b>jitter 식의 형태</b> — AWS Architecture Blog (2015) 의 비교에서 "equal jitter" (절반 고정 +
 *       절반 random) 와 "decorrelated" (이전 wait 기반) 가 thundering herd 차단에서 가장 유사한
 *       성능. 우리는 *equal jitter* 의 단순 형태 (capped × (1 - f + 2f × rand())) 를 채택 — 평균
 *       wait 가 capped 와 같아 직관적, 분산은 jitterFactor 로 조절.</li>
 * </ul>
 */
public final class RetryBackoff {

    private final RetryProperties props;
    private final DoubleSupplier random;

    public RetryBackoff(RetryProperties props) {
        this(props, () -> ThreadLocalRandom.current().nextDouble());
    }

    /** 테스트용 — 결정적 random 공급자 주입. */
    public RetryBackoff(RetryProperties props, DoubleSupplier random) {
        this.props = props;
        this.random = random;
    }

    /**
     * @param attempt 1-based. 첫 호출 *후* 첫 retry 의 wait 시간을 구할 때 attempt=1.
     * @return wait 시간 (ms).
     */
    public long delayMillis(int attempt) {
        if (attempt < 1) attempt = 1;
        double raw = props.baseDelayMs() * Math.pow(props.multiplier(), attempt - 1);
        double capped = Math.min(raw, (double) props.maxDelayMs());
        if (props.jitterFactor() <= 0.0) {
            return Math.max(0L, Math.round(capped));
        }
        double f = props.jitterFactor();
        double r = random.getAsDouble();           // [0, 1)
        double scaled = capped * (1.0 - f + 2.0 * f * r);
        return Math.max(0L, Math.round(scaled));
    }
}
