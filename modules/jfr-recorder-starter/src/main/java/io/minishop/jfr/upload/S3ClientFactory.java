package io.minishop.jfr.upload;

import io.micrometer.core.instrument.MeterRegistry;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;

/**
 * S3 의존성을 *이 한 파일에만* 격리. {@link io.minishop.jfr.JfrAutoConfiguration} 본체는
 * AWS SDK 의 클래스를 직접 참조하지 않게 — Spring 의 {@code BeanTypeDeductionException}
 * 단계에서 reflection 이 메서드 시그니처를 읽다가 NoClassDefFoundError 를 만나는 회귀를
 * 방지.
 *
 * <p>SDK 가 classpath 에 없으면 이 클래스 자체가 *로딩 시도조차 안 되도록* AutoConfiguration
 * 이 사전 검사 후에만 호출. compileOnly 의존이라 사용자 앱에서 SDK 를 안 끼우면 자동으로
 * noop uploader 가 선택되고 이 factory 는 호출되지 않는다.
 */
public final class S3ClientFactory {

    private S3ClientFactory() {}

    public static JfrChunkUploader build(JfrUploadProperties props, MeterRegistry meterRegistry) {
        S3ClientBuilder b = S3Client.builder()
                .region(Region.of(props.region()))
                .serviceConfiguration(S3Configuration.builder()
                        // MinIO 는 virtual-hosted-style (bucket.endpoint) 를 기본 미지원 — path-style
                        // 이 양쪽 호환에 안전.
                        .pathStyleAccessEnabled(true)
                        .build());

        if (props.endpoint() != null && !props.endpoint().isBlank()) {
            b = b.endpointOverride(URI.create(props.endpoint()));
        }

        if (props.accessKey() != null && !props.accessKey().isBlank()
                && props.secretKey() != null && !props.secretKey().isBlank()) {
            b = b.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(props.accessKey(), props.secretKey())));
        } else {
            // 운영 권장 — IAM role 기반 default chain. 정적 키가 비어 있을 때 자동 fallback.
            b = b.credentialsProvider(DefaultCredentialsProvider.create());
        }

        return new S3JfrChunkUploader(b.build(), props, meterRegistry);
    }
}
