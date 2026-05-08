package io.minishop.order.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 로그에 식별자 (PII — 개인을 특정할 수 있는 정보 또는 그에 준하는 키) 를 노출할 때의 마스킹 헬퍼.
 *
 * 정책 (ADR-013):
 * - {@code userId} 처럼 사용자에 1:1 로 묶이는 자연 키는 평문으로 로그에 남기지 않는다.
 *   대신 {@link #userId(Long)} 로 짧은 SHA-256 해시 prefix 로 바꿔 남긴다.
 * - {@code orderId} / {@code paymentId} / {@code reservationId} 같은 시스템 내부 surrogate 키는
 *   사용자를 직접 가리키지 않으므로 평문 허용 (운영 시 grep 대상이라 마스킹하면 디버그가 어려워짐).
 * - 헤더에서 받은 외부 트랜잭션 ID 는 trace 로 묶이므로 별도 마스킹 불필요.
 *
 * 해시는 한 번 계산이 가벼운 SHA-256 의 앞 8 자리만 쓴다. 충돌 확률 (Birthday paradox 로 약 ~65k
 * 사용자에서 충돌 가능성이 의미 있게 올라감) 은 *디버그용 식별자* 라는 용도에서 충분히 낮다. 보안용
 * 토큰이 아니므로 키 stretching (반복 해시로 무차별 대입을 늦추는 기법) 은 두지 않는다.
 */
public final class LogIds {

    private LogIds() {}

    public static String userId(Long userId) {
        if (userId == null) return "u:?";
        return "u:" + shortHash(String.valueOf(userId));
    }

    public static String userId(long userId) {
        return "u:" + shortHash(String.valueOf(userId));
    }

    private static String shortHash(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(8);
            for (int i = 0; i < 4; i++) {                  // 4 byte → 8 hex 자리
                sb.append(String.format("%02x", digest[i] & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 은 JDK 표준 — 정상 환경에선 발생하지 않음.
            return "??";
        }
    }
}
