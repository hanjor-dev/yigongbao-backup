package com.yigongbao.backup.backup;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;
import com.yigongbao.backup.storage.local.LocalDirectoryArtifactStore;

import static org.assertj.core.api.Assertions.assertThat;

/** 元数据模拟制品目录与路径安全测试。 */
class MetadataSimulationArtifactWriterTest {
    @TempDir Path root;

    @Test
    void writesManifestSummaryAndSafeZeroBytePlaceholders() throws Exception {
        BackupManifest manifest = new BackupManifest("6", "2026-07-02T13:14:13Z", List.of(
                new BackupManifest.Asset("a1", "report.pdf", "REPORT", 12L, "https://cos.example/file?signature=secret"),
                new BackupManifest.Asset("a2", "../report.pdf", "SOURCE", 30L, "signed-2"),
                new BackupManifest.Asset("a3", "CON", "SOURCE", 5L, "signed-3"),
                new BackupManifest.Asset("a4", "manifest.json", "SOURCE", 6L, "signed-4"),
                new BackupManifest.Asset("x".repeat(200), "manifest.json", "SOURCE", 7L, "signed-5")
        ), "revision", "manifest-sha", "relations", Map.of("orderNo", "20260702000002"));
        Path hotRoot = root.resolve("hot");
        MetadataSimulationArtifactWriter writer = new MetadataSimulationArtifactWriter(
                root.resolve("simulation"), new LocalDirectoryArtifactStore(hotRoot), new ObjectMapper());

        SimulationArtifactResult result = writer.write(99L, "job-99", "20261104", 6L,
                "20260702000002", manifest);

        assertThat(result.expectedFileCount()).isEqualTo(5);
        assertThat(result.expectedBytes()).isEqualTo(60L);
        assertThat(result.directory().toString()).startsWith(root.resolve("simulation").toAbsolutePath().normalize().toString());
        assertThat(Files.exists(result.directory())).isFalse();
        assertThat(result.storageKey()).isEqualTo("orders/20261104/20260702000002-6.zip");
        assertThat(Files.isRegularFile(hotRoot.resolve(result.storageKey()))).isTrue();
        try (ZipFile zip = new ZipFile(hotRoot.resolve(result.storageKey()).toFile())) {
            assertThat(zip.getEntry("manifest.json")).isNotNull();
            assertThat(zip.getEntry("_simulation.json")).isNotNull();
            assertThat(zip.stream().filter(entry -> entry.getName().startsWith("files/")).count()).isEqualTo(5);
            assertThat(zip.stream().filter(entry -> entry.getName().startsWith("files/"))
                    .allMatch(entry -> entry.getSize() == 0L)).isTrue();
            String manifestText;
            try (var input = zip.getInputStream(zip.getEntry("manifest.json"))) {
                manifestText = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            assertThat(manifestText).contains("\"sourceKey\" : null").doesNotContain("signature=secret");
            String summaryText;
            try (var input = zip.getInputStream(zip.getEntry("_simulation.json"))) {
                summaryText = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            assertThat(summaryText).contains("\"simulated\" : true", "\"jobId\" : 99");
        }
    }

    @Test
    void repeatedWriteReusesCompleteArtifactWithoutDestroyingIt() throws Exception {
        BackupManifest manifest = new BackupManifest("6", "2026-07-02T13:14:13Z",
                List.of(new BackupManifest.Asset("a1", "report.pdf", "REPORT", 12L, "signed")));
        MetadataSimulationArtifactWriter writer = new MetadataSimulationArtifactWriter(root,
                new LocalDirectoryArtifactStore(root.resolve("hot")), new ObjectMapper());
        SimulationArtifactResult first = writer.write(99L, "job-99", "20261104", 6L, "O-6", manifest);
        SimulationArtifactResult second = writer.write(99L, "job-99", "20261104", 6L, "O-6", manifest);

        assertThat(second.directory()).isEqualTo(first.directory());
        assertThat(Files.exists(second.directory())).isFalse();
        assertThat(Files.isRegularFile(root.resolve("hot").resolve(second.storageKey()))).isTrue();
    }
}
