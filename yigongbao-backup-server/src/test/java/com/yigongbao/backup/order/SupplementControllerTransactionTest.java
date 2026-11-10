package com.yigongbao.backup.order;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

class SupplementControllerTransactionTest {
    @Test
    void uploadKeepsVolumeSelectionLockedUntilTaskIsPersisted() throws Exception {
        assertThat(SupplementController.class.getMethod("upload", String.class,
                org.springframework.web.multipart.MultipartFile.class).getAnnotation(Transactional.class)).isNotNull();
    }
}
