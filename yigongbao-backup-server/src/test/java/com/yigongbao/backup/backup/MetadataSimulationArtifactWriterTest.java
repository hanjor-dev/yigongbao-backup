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
        assertThat(result.directory()).startsWith(root.resolve("simulation").toAbsolutePath().normalize());
        assertThat(result.storageKey()).isEqualTo("orders/20261104/20260702000002-6.zip");
        assertThat(Files.isRegularFile(hotRoot.resolve(result.storageKey()))).isTrue();
        try (ZipFile zip = new ZipFile(hotRoot.resolve(result.storageKey()).toFile())) {
            assertThat(zip.getEntry("manifest.json")).isNotNull();
            assertThat(zip.getEntry("_simulation.json")).isNotNull();
            assertThat(zip.stream().filter(entry -> entry.getName().startsWith("files/")).count()).isEqualTo(5);
            assertThat(zip.stream().filter(entry -> entry.getName().startsWith("files/"))
                    .allMatch(entry -> entry.getSize() == 0L)).isTrue();
        }
        assertThat(Files.readString(result.directory().resolve("manifest.json")))
                .contains("20260702000002", "[PRESIGNED_URL_REDACTED]")
                .doesNotContain("signature=secret");
        var summary = new ObjectMapper().readTree(result.directory().resolve("_simulation.json").toFile());
        assertThat(summary.path("simulated").asBoolean()).isTrue();
        assertThat(summary.path("jobId").asLong()).isEqualTo(99L);
        try (var files = Files.walk(result.directory())) {
            assertThat(files.filter(Files::isRegularFile).toList()).hasSize(7);
        }
        try (var files = Files.walk(result.directory().resolve("files"))) {
            assertThat(files.filter(Files::isRegularFile)
                    .allMatch(path -> uncheckedSize(path) == 0L)).isTrue();
        }
    }

    @Test
    void repeatedWriteReusesCompleteArtifactWithoutDestroyingIt() throws Exception {
        BackupManifest manifest = new BackupManifest("6", "2026-07-02T13:14:13Z",
                List.of(new BackupManifest.Asset("a1", "report.pdf", "REPORT", 12L, "signed")));
        MetadataSimulationArtifactWriter writer = new MetadataSimulationArtifactWriter(root,
                new LocalDirectoryArtifactStore(root.resolve("hot")), new ObjectMapper());
        SimulationArtifactResult first = writer.write(99L, "job-99", "20261104", 6L, "O-6", manifest);
        Files.writeString(first.directory().resolve("completed.marker"), "keep");

        SimulationArtifactResult second = writer.write(99L, "job-99", "20261104", 6L, "O-6", manifest);

        assertThat(second.directory()).isEqualTo(first.directory());
        assertThat(Files.readString(second.directory().resolve("completed.marker"))).isEqualTo("keep");
    }

    private static long uncheckedSize(Path path) {
        try { return Files.size(path); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
}
