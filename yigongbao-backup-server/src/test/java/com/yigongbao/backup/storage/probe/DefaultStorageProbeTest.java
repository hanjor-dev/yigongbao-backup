package com.yigongbao.backup.storage.probe;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultStorageProbeTest {
    @TempDir
    Path tempDir;

    @Test
    void createsMissingStorageDirectoryBeforeProbingCapacity() throws Exception {
        Path missing = tempDir.resolve("hot-data").resolve("nested");

        var snapshot = new DefaultStorageProbe().probe(missing);

        assertThat(Files.isDirectory(missing)).isTrue();
        assertThat(snapshot.capacityKnown()).isTrue();
        assertThat(snapshot.totalBytes()).isGreaterThan(0);
        assertThat(snapshot.usableBytes()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void reportsUnknownCapacityWhenConfiguredPathIsAFile() throws Exception {
        Path file = tempDir.resolve("hot-data");
        Files.writeString(file, "not a directory");

        var snapshot = new DefaultStorageProbe().probe(file);

        assertThat(snapshot.capacityKnown()).isFalse();
        assertThat(snapshot.totalBytes()).isEqualTo(-1);
        assertThat(snapshot.usableBytes()).isEqualTo(-1);
    }
}
