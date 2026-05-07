package io.minishop.slowquery;

import java.util.regex.Pattern;

/**
 * 같은 "쿼리 모양" 을 같은 키로 묶기 위한 정규화기.
 * SELECT 의 `WHERE id = 42` 와 `WHERE id = 43` 를 같은 패턴 (`where id = ?`) 으로 본다.
 *
 * 단순한 휴리스틱 (정확하진 않아도 대부분의 경우에 잘 동작하는 단순 규칙):
 *  - 숫자 리터럴 → ?
 *  - '문자열' → ?
 *  - 연속된 공백 → 한 칸
 *  - 끝의 세미콜론 제거
 *
 * production 수준의 SQL 파서는 아니지만 N+1 추적 (1번의 마스터 조회 + N번의 자식 조회를 잡아내는
 * 로직) 엔 충분하다.
 */
final class SqlNormalizer {

    private static final Pattern STRING_LITERAL = Pattern.compile("'(?:[^']|'')*'");
    private static final Pattern NUMERIC_LITERAL = Pattern.compile("\\b\\d+(\\.\\d+)?\\b");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private SqlNormalizer() {}

    static String normalize(String sql) {
        if (sql == null || sql.isBlank()) return "";
        String s = STRING_LITERAL.matcher(sql).replaceAll("?");
        s = NUMERIC_LITERAL.matcher(s).replaceAll("?");
        s = WHITESPACE.matcher(s).replaceAll(" ");
        s = s.trim();
        if (s.endsWith(";")) s = s.substring(0, s.length() - 1).trim();
        return s.toLowerCase();
    }
}
