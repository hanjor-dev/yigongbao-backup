package com.yigongbao.backup.storage.local;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LocalDirectoryArtifactStoreSafetyContractTest {

    @Test
    void rejectsWindowsJunctionsAndOtherReparsePointsWithoutFollowingThem() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/yigongbao/backup/storage/local/LocalDirectoryArtifactStore.java"));

        assertThat(source).contains("BasicFileAttributes", "LinkOption.NOFOLLOW_LINKS", "attributes.isOther()");
    }
}
