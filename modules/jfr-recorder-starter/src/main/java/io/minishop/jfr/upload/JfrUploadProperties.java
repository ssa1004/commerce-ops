package io.minishop.jfr.upload;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JFR chunk 원격 업로드 설정.
 *
 * <p>설계 결정:
 * <ul>
 *   <li><b>기본 disable</b> — 개발 환경에서는 외부 S3 가 없어도 부팅이 깨지면 안 된다.
 *       dev 에서 S3 끼우려면 minio 컨테이너 추가 후 명시적으로 enable.</li>
 *   <li><b>endpoint override</b> — AWS S3 외에 MinIO / Ceph / R2 같은 S3 호환 스토리지를
 *       endpoint 만 바꿔서 그대로 쓸 수 있게.</li>
 *   <li><b>key prefix</b> — {@code org / region / pod / yyyy / MM / dd / chunk-...jfr} 형식.
 *       prefix 만 보고 retention 정책을 적용할 수 있고, 한 organization 안에 여러 환경 (dev /
 *       staging / prod) 도 prefix 만으로 분리 가능.</li>
 *   <li><b>pod identity</b> — k8s 환경에서 같은 image 를 N 개 띄울 때 chunk 가 서로 덮어쓰지
 *       않도록. {@code HOSTNAME} 환경변수가 자연스러운 기본 (k8s deployment 의 pod 이름).</li>
 *   <li><b>upload-on-rollover-only</b> — ad-hoc dump 는 운영자가 의도적으로 만든 단발성 산출물.
 *       업로드 여부를 별도 flag 로. 기본은 *업로드 안 함* — ad-hoc 은 보통 즉시 분석에 쓰고
 *       사이즈가 작아서 굳이 원격 보존이 필요 없음.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "mini-shop.jfr.upload")
public record JfrUploadProperties(
        boolean enabled,
        String backend,           // "s3" / "minio" / "noop"
        String bucket,
        String region,
        String endpoint,          // null/blank 이면 AWS 기본 endpoint
        String keyPrefix,         // 예: "minishop/dev"
        String podId,             // null/blank 이면 HOSTNAME 환경변수
        String accessKey,         // dev/local 전용. 운영은 IAM role.
        String secretKey,         // 동일.
        boolean uploadAdHocDumps,
        int maxRemoteListing,
        boolean abortOnUploadError
) {
    public JfrUploadProperties {
        if (backend == null || backend.isBlank()) {
            backend = "noop";
        }
        if (keyPrefix == null) {
            keyPrefix = "";
        }
        if (region == null || region.isBlank()) {
            // AWS SDK 가 region 을 강제 — 명시 안 했을 때 fallback. MinIO 는 region 무관
            // 이지만 SDK 가 어쨌든 하나를 요구함.
            region = "us-east-1";
        }
        if (maxRemoteListing <= 0) {
            // /actuator/jfr 응답이 너무 무거워지지 않게.
            maxRemoteListing = 200;
        }
        if (podId == null || podId.isBlank()) {
            String hostname = System.getenv("HOSTNAME");
            podId = (hostname == null || hostname.isBlank()) ? "local" : hostname;
        }
    }

    /** noop / disabled 가 아닌 *실 백엔드* 를 쓰는지. */
    public boolean isActive() {
        return enabled && !"noop".equalsIgnoreCase(backend);
    }
}
