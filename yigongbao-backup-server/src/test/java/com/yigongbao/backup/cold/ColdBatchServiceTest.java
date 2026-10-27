package com.yigongbao.backup.cold;

import com.yigongbao.backup.storage.local.LocalDirectoryArtifactStore;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.List;
import java.util.zip.ZipFile;
import static org.assertj.core.api.Assertions.assertThat;

class ColdBatchServiceTest {
    @Test void writesFormatConsumedByImportService() throws Exception {
        Path hot = Files.createTempDirectory("hot");
        Path cold = Files.createTempDirectory("cold");
        Files.createDirectories(hot.resolve("orders/O1"));
        Files.writeString(hot.resolve("orders/O1/O1.zip"), "payload");
        Path archive = new ColdBatchService(new LocalDirectoryArtifactStore(hot))
                .writeBatch("B1", cold, List.of("orders/O1/O1.zip"));
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            assertThat(zip.getEntry("orders/O1/original/O1.zip")).isNotNull();
            assertThat(zip.getEntry("batch-manifest.json")).isNotNull();
            assertThat(zip.getEntry("_COMPLETE")).isNotNull();
        }
        assertThat(new ColdImportService(cold, new LocalDirectoryArtifactStore(Files.createTempDirectory("hot2")))
                .restore("B1.zip", java.util.Set.of("O1"))).isEqualTo(1);
    }
}
