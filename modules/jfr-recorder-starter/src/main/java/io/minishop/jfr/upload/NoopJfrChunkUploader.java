package io.minishop.jfr.upload;

import java.nio.file.Path;
import java.util.List;

/**
 * 업로드 비활성 환경의 fallback. {@link io.minishop.jfr.JfrRecorder} 는 항상 한
 * uploader 를 호출하는 단순한 형태로 두기 위해 *null 대신* noop 구현으로 채운다.
 *
 * <p>upload() 는 {@code null} 을 반환 — Recorder 는 null 을 *업로드 안 함* 으로 해석.
 * 메트릭/로그는 발생시키지 않음 (실 백엔드와 동등한 비교를 흐리지 않게).
 */
public class NoopJfrChunkUploader implements JfrChunkUploader {

    @Override
    public String upload(Path localChunk) {
        return null;
    }

    @Override
    public List<String> listRemote(int maxItems) {
        return List.of();
    }

    @Override
    public String backendName() {
        return "noop";
    }
}
