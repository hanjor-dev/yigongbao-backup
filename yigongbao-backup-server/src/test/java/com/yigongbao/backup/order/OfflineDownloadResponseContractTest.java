package com.yigongbao.backup.order;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class OfflineDownloadResponseContractTest {
    @Test
    void offlineDownloadsUseFailureEnvelopeInsteadOfOkEnvelope() throws Exception {
        String order = Files.readString(Path.of("src/main/java/com/yigongbao/backup/order/OrderController.java"));
        String supplement = Files.readString(Path.of("src/main/java/com/yigongbao/backup/order/SupplementController.java"));
        assertThat(order).contains("ApiResponse.error(\"STORAGE_VOLUME_OFFLINE\"");
        assertThat(supplement).contains("ApiResponse.error(", "\"STORAGE_VOLUME_OFFLINE\"");
    }
}
