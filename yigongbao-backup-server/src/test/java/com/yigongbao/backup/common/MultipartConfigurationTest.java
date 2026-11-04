package com.yigongbao.backup.common;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** 防止开发环境未启用 prod profile 时回退到 Spring 默认 1MB 上传限制。 */
class MultipartConfigurationTest {
    @Test
    void baseApplicationConfigDeclaresMultipartLimit() throws Exception {
        String yaml = new String(getClass().getResourceAsStream("/application.yml").readAllBytes(), StandardCharsets.UTF_8);
        assertThat(yaml).contains("max-file-size: ${BACKUP_MAX_UPLOAD_SIZE:")
                .contains("max-request-size: ${BACKUP_MAX_UPLOAD_SIZE:");
    }
}
