package io.minishop.payment.util

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

/**
 * 로그에 식별자 (PII — 개인을 특정할 수 있는 정보 또는 그에 준하는 키) 를 노출할 때의 마스킹 헬퍼.
 *
 * 정책 (ADR-013):
 * - `userId` 처럼 사용자에 1:1 로 묶이는 자연 키는 평문으로 로그에 남기지 않는다. 대신
 *   [userId] 로 짧은 SHA-256 해시 prefix 로 바꿔 남긴다.
 * - `orderId` / `paymentId` / `reservationId` 같은 시스템 내부 surrogate 키는 사용자를 직접
 *   가리키지 않으므로 평문 허용 (운영 시 grep 대상이라 마스킹하면 디버그가 어려워짐).
 *
 * 해시는 SHA-256 의 앞 8 자리만 쓴다. 보안용 토큰이 아닌 *디버그용 식별자* 가 목적이라 키
 * stretching (반복 해시로 무차별 대입을 늦추는 기법) 은 두지 않는다.
 */
object LogIds {

    @JvmStatic
    fun userId(userId: Long?): String {
        if (userId == null) return "u:?"
        return "u:" + shortHash(userId.toString())
    }

    private fun shortHash(value: String): String {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(value.toByteArray(StandardCharsets.UTF_8))
            val sb = StringBuilder(8)
            for (i in 0 until 4) {
                sb.append(String.format("%02x", digest[i].toInt() and 0xff))
            }
            sb.toString()
        } catch (_: NoSuchAlgorithmException) {
            "??"
        }
    }
}
