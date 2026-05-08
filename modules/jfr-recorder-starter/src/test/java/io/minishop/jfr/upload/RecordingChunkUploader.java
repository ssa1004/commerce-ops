package io.minishop.jfr.upload;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 테스트 전용 fake — 실제 S3 없이 업로드 호출이 일어났는지/언제 일어났는지/실패 주입을
 * 시뮬레이션한다. 업로드 큐를 `ArrayList` 가 아니라 `CopyOnWriteArrayList` 로 두는 이유는
 * Recorder 의 jfr-uploader 스레드가 호출하기 때문 — 테스트 스레드에서 상태를 읽을 때
 * 안전한 visibility 를 보장.
 */
public class RecordingChunkUploader implements JfrChunkUploader {

    private final List<Path> uploaded = new CopyOnWriteArrayList<>();
    private final List<String> remote = new ArrayList<>();
    private volatile boolean failNext = false;

    @Override
    public synchronized String upload(Path localChunk) {
        if (failNext) {
            failNext = false;
            throw new UploadException("induced", new RuntimeException("test"));
        }
        uploaded.add(localChunk);
        String id = "fake://test/" + localChunk.getFileName().toString();
        remote.add(id);
        return id;
    }

    @Override
    public List<String> listRemote(int maxItems) {
        return List.copyOf(remote);
    }

    @Override
    public String backendName() {
        return "test-fake";
    }

    public List<Path> uploaded() {
        return List.copyOf(uploaded);
    }

    public void failNextUpload() {
        this.failNext = true;
    }
}
