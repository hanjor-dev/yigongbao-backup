package com.yigongbao.backup.order;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SupplementStagingServiceTest {
    @TempDir Path directory;

    @Test
    void cleanupDeletesOnlyExpiredManagedPartFiles() throws Exception {
        SupplementStagingService service = new SupplementStagingService(directory);
        Path expired = directory.resolve("upload-old.part");
        Path recent = directory.resolve("upload-new.part");
        Path unrelated = directory.resolve("keep.txt");
        Files.writeString(expired, "old");
        Files.writeString(recent, "new");
        Files.writeString(unrelated, "keep");
        Files.setLastModifiedTime(expired, FileTime.from(Instant.now().minusSeconds(7200)));

        int deleted = service.cleanupBefore(Instant.now().minusSeconds(3600));

        assertThat(deleted).isEqualTo(1);
        assertThat(expired).doesNotExist();
        assertThat(recent).exists();
        assertThat(unrelated).exists();
    }
}
