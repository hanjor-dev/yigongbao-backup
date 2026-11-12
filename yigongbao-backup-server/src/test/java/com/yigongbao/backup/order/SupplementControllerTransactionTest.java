package com.yigongbao.backup.order;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;

class SupplementControllerTransactionTest {
    @Test
    void uploadKeepsVolumeSelectionLockedUntilTaskIsPersisted() throws Exception {
        assertThat(SupplementController.class.getMethod("upload", String.class,
                org.springframework.web.multipart.MultipartFile.class).getAnnotation(Transactional.class)).isNotNull();
    }

    @Test
    void uploadValidatesBeforeWritingAndDoesNotStageInsideTheManagedVolume() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/yigongbao/backup/order/SupplementController.java"));

        assertThat(source.indexOf("supplements.validateUpload")).isPositive();
        assertThat(source.indexOf("supplements.validateUpload"))
                .isLessThan(source.indexOf("file.transferTo"));
        assertThat(source).doesNotContain("store.resolve(volume.getId(), STAGING_DIRECTORY)");
    }

    @Test
    void handlerAlwaysDeletesStagedFileEvenWhenValidationOrUploadFails() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/yigongbao/backup/order/SupplementUploadJobHandler.java"));

        assertThat(source).containsPattern("finally\\s*\\{\\s*stagingFiles\\.delete\\(staged\\)");
    }
}
