package io.minishop.jfr;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * actuator endpoint — 운영자가 API 로 JFR 상태를 조회하거나 ad-hoc dump 를 트리거할 수 있게.
 *
 * <p>endpoint id = "jfr". expose:
 * <ul>
 *   <li>GET  /actuator/jfr — 상태 + 누적 chunk 목록</li>
 *   <li>POST /actuator/jfr/{tag} — ad-hoc dump (tag 가 파일명에 끼워짐)</li>
 * </ul>
 *
 * <p>운영 권한 모델: actuator 의 다른 sensitive endpoint 와 같은 보호 (Spring Security / 네트워크
 * 격리 / 운영 VPN 등) 를 따른다 — 이 모듈은 *endpoint 만 제공*, 보호는 호출자 앱이 책임.
 */
@Endpoint(id = "jfr")
public class JfrEndpoint {

    private final JfrRecorder recorder;

    public JfrEndpoint(JfrRecorder recorder) {
        this.recorder = recorder;
    }

    @ReadOperation
    public Map<String, Object> status() {
        List<Path> chunks = recorder.listChunks();
        Instant startedAt = recorder.getStartedAt();
        return Map.of(
                "active", recorder.isStarted(),
                "startedAt", startedAt != null ? startedAt.toString() : "",
                "chunkCount", chunks.size(),
                "chunks", chunks.stream().map(p -> p.getFileName().toString()).toList()
        );
    }

    @WriteOperation
    public Map<String, Object> dump(@Selector String tag) {
        Path dumped = recorder.dump(tag);
        if (dumped == null) {
            return Map.of("ok", false, "reason", "JFR not active or dump failed");
        }
        return Map.of(
                "ok", true,
                "file", dumped.toString()
        );
    }
}
