package io.minishop.jfr;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * JFR (Java Flight Recorder — JDK 표준 저오버헤드 프로파일러) 의 always-on 설정.
 *
 * <p>운영 표준은 Datadog Continuous Profiler / NHN APM / 라인 LINE Profiler 처럼 *24/7 켜둔 채*
 * 일정 주기로 chunk 를 롤오버해 디스크/오브젝트 스토리지에 적재하는 형태. 이 모듈도 같은 모양이다.
 *
 * <p>오버헤드: default 설정은 ~1% (CPU/throughput), profile 설정은 ~3%. 운영 권장은 default.
 *
 * <ul>
 *   <li>{@code rollover}: 한 chunk 의 길이. 짧을수록 손실 윈도우 작지만 파일 수 많음.</li>
 *   <li>{@code maxRetained}: 디렉토리에 보존할 최근 chunk 수. 오래된 chunk 는 삭제.</li>
 *   <li>{@code dumpDirectory}: chunk 가 떨어지는 자리. 운영은 보통 별도 볼륨 (NFS/EBS) 또는
 *       업로드 후 삭제.</li>
 *   <li>{@code settings}: JFR 설정 이름 — "default" (저오버헤드, 운영용) 또는 "profile" (할당
 *       샘플링까지 포함, 진단용).</li>
 *   <li>{@code maskSensitiveEvents}: SocketRead/Write 같은 이벤트의 host/address 를 비활성화.
 *       PII 보호 — JFR 은 원래 운영자만 볼 의도이지만, chunk 가 외부 스토리지로 옮겨지면 그쪽
 *       의 권한 모델을 따라가므로 *발생 시점에* 거르는 편이 안전.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "mini-shop.jfr")
public record JfrRecorderProperties(
        boolean enabled,
        Duration rollover,
        int maxRetained,
        String dumpDirectory,
        String settings,
        boolean maskSensitiveEvents
) {
    public JfrRecorderProperties {
        if (rollover == null || rollover.isZero() || rollover.isNegative()) {
            // 5분 — Datadog Continuous Profiler 의 60s 와 NHN APM 의 5m 사이. always-on 의 기본은
            // *복구 가능한 윈도우* 가 너무 길지 않게. 5분이면 평균 파일 크기 ~10MB 수준.
            rollover = Duration.ofMinutes(5);
        }
        if (maxRetained <= 0) {
            // 24개 = 5분 × 24 = 2시간. 대시보드/리소스 알람을 보고 사후 분석 시작하기까지 평균
            // 30분 ~ 1시간이라는 SRE 경험치가 있어 2배 버퍼.
            maxRetained = 24;
        }
        if (dumpDirectory == null || dumpDirectory.isBlank()) {
            dumpDirectory = "/tmp/jfr";
        }
        if (settings == null || settings.isBlank()) {
            // "default" / "profile" 만 jdk.jfr 표준. profile 은 오버헤드 ~3% 라 항상 켜기엔 부담.
            settings = "default";
        }
    }
}
