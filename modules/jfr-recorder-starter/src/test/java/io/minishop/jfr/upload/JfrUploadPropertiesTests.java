package io.minishop.jfr.upload;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JfrUploadPropertiesTests {

    @Test
    void appliesDefaults_whenBlank() {
        JfrUploadProperties p = new JfrUploadProperties(
                false, null, null, null, null, null, null, null, null, false, 0, false);

        // backend=null → "noop", region=null → "us-east-1" (SDK 강제), maxRemoteListing=0 → 200
        assertThat(p.backend()).isEqualTo("noop");
        assertThat(p.region()).isEqualTo("us-east-1");
        assertThat(p.maxRemoteListing()).isEqualTo(200);
        assertThat(p.podId()).isNotBlank();
        assertThat(p.isActive()).isFalse();
    }

    @Test
    void enabledWithS3Backend_isActive() {
        JfrUploadProperties p = new JfrUploadProperties(
                true, "s3", "my-bucket", "us-west-2", null, "minishop/dev",
                "pod-1", "AKIA...", "secret", false, 100, false);

        assertThat(p.isActive()).isTrue();
        assertThat(p.podId()).isEqualTo("pod-1");
    }

    @Test
    void enabledWithNoopBackend_isInactive() {
        // enabled=true 라도 backend=noop 이면 *실 백엔드를 안 쓰는* 상태로 평가.
        JfrUploadProperties p = new JfrUploadProperties(
                true, "noop", null, null, null, null, null, null, null, false, 0, false);
        assertThat(p.isActive()).isFalse();
    }

    @Test
    void disabledOverridesBackend() {
        JfrUploadProperties p = new JfrUploadProperties(
                false, "s3", "b", "r", null, null, null, null, null, false, 0, false);
        assertThat(p.isActive()).isFalse();
    }
}
