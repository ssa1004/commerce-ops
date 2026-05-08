package io.minishop.jfr.upload;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * AWS S3 / S3 호환 (MinIO, R2, Ceph) 위로 JFR chunk 를 업로드하는 구현.
 *
 * <p>설계 결정:
 * <ul>
 *   <li><b>S3Client 는 외부에서 주입</b> — auto-config 가 properties 보고 만든다. 테스트는
 *       fake 를 만들어 끼울 수 있게 하고, 운영은 IAM role 기반 default credential chain 에
 *       맡길 수 있게.</li>
 *   <li><b>key 구조</b> — {@code prefix/podId/yyyy/MM/dd/HHmmss-filename}. 시간 prefix 로
 *       lifecycle 정책 (예: 90일 후 Glacier 이관) 적용이 쉽다. podId 분리로 같은 deployment
 *       의 여러 인스턴스가 충돌 없이 함께 쓰는 걸 보장.</li>
 *   <li><b>list 는 직접 prefix 만</b> — listRemote() 가 온 버킷을 다 스캔하지 않게. prefix
 *       (organization + podId 까지) 만 listing → 비용 낮고 응답 빠름.</li>
 *   <li><b>예외 wrap</b> — SDK 의 S3Exception 을 그대로 노출하면 호출자 (Recorder) 가 SDK 에
 *       강하게 묶임. {@link UploadException} 으로 묶어 격리.</li>
 * </ul>
 *
 * <p>오버헤드: chunk 한 개 upload ≈ 10MB × upstream throughput. JFR 의 5분 rollover 와 비교하면
 * 무시할 수준이지만, 업로드가 늦어질 경우 다음 rollover 가 와도 *현재 업로드는 그대로 진행* —
 * Recorder 의 별도 single-thread executor 에서 직렬로 처리.
 */
public class S3JfrChunkUploader implements JfrChunkUploader {

    private static final Logger log = LoggerFactory.getLogger(S3JfrChunkUploader.class);

    /** key 의 시간 prefix 포맷 — yyyy/MM/dd 단위 lifecycle 정책 적용을 쉽게 한다. */
    private static final DateTimeFormatter DATE_PREFIX = DateTimeFormatter
            .ofPattern("yyyy/MM/dd/HHmmss")
            .withZone(ZoneOffset.UTC);

    private final S3Client s3;
    private final JfrUploadProperties props;
    private final MeterRegistry meterRegistry;

    public S3JfrChunkUploader(S3Client s3, JfrUploadProperties props, MeterRegistry meterRegistry) {
        this.s3 = s3;
        this.props = props;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public String upload(Path localChunk) throws UploadException {
        Timer.Sample sample = Timer.start(meterRegistry);
        String key = buildKey(localChunk);
        PutObjectRequest req = PutObjectRequest.builder()
                .bucket(props.bucket())
                .key(key)
                // chunk 는 변경 불가 (immutable) — 업로드 후 덮어쓰일 일이 없게 하는 안전장치는
                // 운영에서 bucket policy (deny PutObject if-exists) 로 추가. 본 모듈은 단일 PUT
                // 만 책임.
                .contentType("application/octet-stream")
                .build();
        try {
            s3.putObject(req, RequestBody.fromFile(localChunk));
            String identifier = "s3://" + props.bucket() + "/" + key;
            log.info("JFR chunk uploaded: local={} remote={}", localChunk.getFileName(), identifier);
            count("ok");
            sample.stop(meterRegistry.timer("jfr.upload.duration", Tags.of("backend", backendName())));
            return identifier;
        } catch (S3Exception e) {
            count("error");
            sample.stop(meterRegistry.timer("jfr.upload.duration", Tags.of("backend", backendName())));
            throw new UploadException(
                    "S3 putObject failed: bucket=" + props.bucket() + " key=" + key, e);
        }
    }

    @Override
    public List<String> listRemote(int maxItems) {
        try {
            String prefix = props.keyPrefix().isBlank()
                    ? props.podId() + "/"
                    : trimSlash(props.keyPrefix()) + "/" + props.podId() + "/";
            ListObjectsV2Request req = ListObjectsV2Request.builder()
                    .bucket(props.bucket())
                    .prefix(prefix)
                    .maxKeys(Math.min(maxItems, props.maxRemoteListing()))
                    .build();
            ListObjectsV2Response res = s3.listObjectsV2(req);
            return res.contents().stream()
                    .map(o -> "s3://" + props.bucket() + "/" + o.key())
                    .toList();
        } catch (S3Exception e) {
            // listing 실패는 업로드와 격리 — endpoint 호출자에게 빈 목록을 돌려주고 메트릭만 올림.
            // 운영자는 직접 aws s3 ls 로 확인 가능.
            log.warn("JFR remote list failed: {}", e.getMessage());
            count("list_error");
            return List.of();
        }
    }

    @Override
    public String backendName() {
        // endpoint 가 비어있지 않으면 (S3 호환 minio 등) backend 이름을 명시 prop 으로 식별.
        return props.backend();
    }

    private String buildKey(Path localChunk) {
        String filename = localChunk.getFileName().toString();
        String stamp = DATE_PREFIX.format(Instant.now());
        String prefix = props.keyPrefix().isBlank() ? "" : trimSlash(props.keyPrefix()) + "/";
        return prefix + props.podId() + "/" + stamp + "-" + filename;
    }

    private static String trimSlash(String s) {
        String r = s;
        if (r.startsWith("/")) r = r.substring(1);
        if (r.endsWith("/")) r = r.substring(0, r.length() - 1);
        return r;
    }

    private void count(String outcome) {
        meterRegistry.counter("jfr.upload.events",
                "backend", backendName(),
                "outcome", outcome).increment();
    }
}
