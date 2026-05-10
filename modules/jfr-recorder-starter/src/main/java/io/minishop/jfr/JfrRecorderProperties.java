package io.minishop.jfr;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * JFR (Java Flight Recorder — JDK 표준 저오버헤드 프로파일러) 의 always-on 설정.
 *
 * <p>continuous profiling 의 일반 패턴 — 24/7 켜둔 채 일정 주기로 chunk 를 롤오버해 디스크/오브젝트
 * 스토리지에 적재하는 형태. 이 모듈도 같은 모양이다.
 *
 * <p>오버헤드: default 설정은 ~1% (CPU/throughput), profile 설정은 ~3%. 운영 권장은 default.
 *
 * <ul>
 *   <li>{@code rollover}: 한 chunk 의 길이. 짧을수록 손실 윈도우 작지만 파일 수 많음.</li>
 *   <li>{@code maxRetained}: 디렉토리에 보존할 최근 chunk *개수*. 오래된 chunk 는 삭제.</li>
 *   <li>{@code maxTotalSize}: 디렉토리에 보존할 chunk 의 *총 크기* 상한. 카운트 cap 적용 후
 *       총 합이 이 값을 넘으면 가장 오래된 것부터 추가 삭제. 평소엔 카운트 cap 이 먼저 닿지만,
 *       burst 부하 / profile 설정 / 큰 heap 등으로 chunk 가 평소보다 커지는 상황에서 디스크
 *       풀을 차단하는 안전망 (ADR-024).</li>
 *   <li>{@code dumpDirectory}: chunk 가 떨어지는 자리. 운영은 보통 별도 볼륨 (NFS/EBS) 또는
 *       업로드 후 삭제.</li>
 *   <li>{@code settings}: JFR 설정 이름 — "default" (저오버헤드, 운영용) 또는 "profile" (할당
 *       샘플링까지 포함, 진단용).</li>
 *   <li>{@code maskSensitiveEvents}: SocketRead/Write 같은 이벤트의 host/address 를 비활성화.
 *       PII 보호 — JFR 은 원래 운영자만 볼 의도이지만, chunk 가 외부 스토리지로 옮겨지면 그쪽
 *       의 권한 모델을 따라가므로 발생 시점에 거르는 편이 안전.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "mini-shop.jfr")
public record JfrRecorderProperties(
        boolean enabled,
        Duration rollover,
        int maxRetained,
        DataSize maxTotalSize,
        String dumpDirectory,
        String settings,
        boolean maskSensitiveEvents
) {
    public JfrRecorderProperties {
        if (rollover == null || rollover.isZero() || rollover.isNegative()) {
            // 5분 — always-on 의 기본은 복구 가능한 윈도우가 너무 길지 않게. 5분이면 평균 파일 크기
            // ~10MB 수준이라 디스크/네트워크 부담도 작다.
            rollover = Duration.ofMinutes(5);
        }
        if (maxRetained <= 0) {
            // 24개 = 5분 × 24 = 2시간. 대시보드/리소스 알람을 보고 사후 분석 시작하기까지 평균
            // 30분 ~ 1시간이라는 SRE 경험치가 있어 2배 버퍼.
            maxRetained = 24;
        }
        if (maxTotalSize == null || maxTotalSize.toBytes() <= 0) {
            // 500MB — default 설정 + 5분 rollover 의 평균 chunk 가 ~10MB 이므로 카운트 cap (24개,
            // 약 240MB) 보다 약 2배 여유. 평소엔 카운트가 먼저 닿고, profile 설정으로 chunk 가
            // 평소의 5배가 되는 단발성 burst 에서만 size cap 이 발동되는 안전망 자리.
            maxTotalSize = DataSize.ofMegabytes(500);
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
