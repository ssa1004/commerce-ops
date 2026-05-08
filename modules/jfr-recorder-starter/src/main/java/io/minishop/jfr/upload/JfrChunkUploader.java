package io.minishop.jfr.upload;

import java.nio.file.Path;
import java.util.List;

/**
 * 디스크에 떨어진 JFR chunk 를 *원격 스토리지* 로 사본을 보내는 추상화.
 *
 * <p>왜 별도 추상화인가:
 * <ul>
 *   <li><b>분리</b> — {@link io.minishop.jfr.JfrRecorder} 는 chunk *생성* 만 책임. 업로드/실패
 *       처리/keying 정책은 이쪽이 책임. 추후 GCS / Azure Blob 추가 시 다른 구현체만 두면 됨.</li>
 *   <li><b>테스트성</b> — 실제 S3 없이 in-memory fake 로 단위 테스트 가능 (LocalStack 같은
 *       무거운 의존 없이).</li>
 *   <li><b>안전한 비활성</b> — uploader bean 이 없으면 Recorder 는 *업로드를 그냥 건너뛴다*
 *       (chunk 자체는 디스크에 그대로). 사용자가 의존성/설정을 안 줘도 starter 부팅이 깨지지
 *       않는 걸 보장하는 자리.</li>
 * </ul>
 *
 * <p>운영 가정:
 * <ul>
 *   <li>chunk dump 는 보통 10MB 안팎이라 단일 PUT 으로 처리 가능 (multi-part 불필요).</li>
 *   <li>업로드 자체가 실패해도 chunk 는 디스크에 그대로 — retention 까지는 살아 있음. retention
 *       만료 전에 다음 rollover 가 다시 시도하지는 않는다 (지금 단순화). 후속에서 retry queue
 *       검토.</li>
 *   <li>업로드 실패율이 임계 초과 시 P2 알람 (ADR-018 참고).</li>
 * </ul>
 */
public interface JfrChunkUploader {

    /**
     * 한 chunk 파일을 원격에 업로드. 실패 시 {@link UploadException} — 호출자는 메트릭만
     * 올리고 디스크 chunk 는 건드리지 않는다.
     *
     * @param localChunk 로컬 디스크 절대경로 (rollover 가 막 떨어뜨린 파일).
     * @return 업로드된 객체의 *원격 식별자* (S3 의 경우 {@code s3://bucket/key}). UI 노출용.
     */
    String upload(Path localChunk) throws UploadException;

    /**
     * 원격에 보존된 chunk 의 *식별자 목록*. {@code /actuator/jfr} 가 local 과 합쳐 보여줌.
     *
     * <p>운영 트래픽 패턴: 사고 발생 후 운영자가 chunk 를 빨리 찾고 싶을 때 호출. 호출 빈도가
     * 낮고 버킷 prefix 가 시간 단위로 분산되어 있어 list 비용은 무시할 수준.
     *
     * @param maxItems 상한. 내부적으로 더 많은 객체가 있어도 잘라서 반환.
     */
    List<String> listRemote(int maxItems);

    /** 런타임 식별 (메트릭 태그 / 로그). 예: {@code "s3"}, {@code "minio"}, {@code "noop"}. */
    String backendName();

    /**
     * 업로드 실패. {@code UploadException} 자체로 그치게 — 사용자 비즈니스 흐름까지 막지 않음.
     */
    class UploadException extends RuntimeException {
        public UploadException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
