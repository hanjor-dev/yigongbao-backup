package com.yigongbao.backup.backup;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.security.MessageDigest;
import java.io.InputStream;
import com.yigongbao.backup.storage.api.ArtifactStore;

/** 在隔离目录生成 Manifest、模拟摘要和零字节文件占位符。 */
public class MetadataSimulationArtifactWriter {
    private static final Set<String> WINDOWS_RESERVED = Set.of(
            "CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");
    private final Path root;
    private final ArtifactStore store;
    private final ObjectMapper mapper;

    /** 创建绑定到指定隔离根目录的写入器。 */
    public MetadataSimulationArtifactWriter(Path root, ArtifactStore store, ObjectMapper mapper) {
        if (root == null) throw new IllegalArgumentException("模拟制品根目录不能为空");
        this.root = root.toAbsolutePath().normalize();
        this.store = java.util.Objects.requireNonNull(store, "store");
        this.mapper = mapper;
    }

    /** 生成单个订单的模拟制品，并在压缩包写入热备后清理临时目录。 */
    public SimulationArtifactResult write(long jobId, String jobNo, String taskDate, long orderId,
                                          String orderNo, BackupManifest manifest) {
        String safeDate = segment(taskDate, "date");
        String safeJob = segment(jobNo, "job");
        String safeOrder = segment(orderNo, "order") + "-" + orderId;
        Path target = inside(root.resolve(safeDate).resolve(safeJob).resolve(safeOrder));
        Path temporary = inside(target.resolveSibling(target.getFileName() + ".tmp-" + System.nanoTime()));
        // 使用稳定的 previous 名称，进程在两次移动之间退出后，下次执行仍能发现并恢复旧制品。
        Path previous = inside(target.resolveSibling(target.getFileName() + ".previous"));
        long expectedBytes = manifest.assets().stream().mapToLong(BackupManifest.Asset::size).sum();
        recoverInterruptedReplacement(previous, target);
        // 任务在数据库落终态前可能因进程退出而被重新领取；完整制品直接幂等复用，避免破坏已完成结果。
        if (isComplete(target)) {
            SimulationArtifactResult result = publishPackage(target, safeDate, safeOrder, manifest.assets());
            deleteQuietly(target);
            return result;
        }
        try {
            Files.createDirectories(temporary);
            mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.resolve("manifest.json").toFile(),
                    redactedManifest(manifest));
            mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.resolve("_simulation.json").toFile(), Map.of(
                    "simulated", true, "executionMode", BackupExecutionMode.METADATA_SIMULATION.name(),
                    "jobId", jobId, "jobNo", jobNo, "orderId", orderId, "orderNo", orderNo,
                    "manifestRevision", nullSafe(manifest.manifestRevision()),
                    "expectedFileCount", manifest.assets().size(), "expectedBytes", expectedBytes,
                    "generatedAt", OffsetDateTime.now().toString()));
            Set<String> names = new HashSet<>(Set.of("manifest.json", "_simulation.json"));
            Path filesDirectory = temporary.resolve("files");
            Files.createDirectories(filesDirectory);
            for (BackupManifest.Asset asset : manifest.assets()) {
                String base = segment(asset.assetId(), "asset") + "-" + segment(asset.fileName(), "asset");
                String candidate = uniqueName(base, asset.assetId(), names);
                Path placeholder = inside(filesDirectory.resolve(candidate));
                Files.createFile(placeholder);
            }
            Files.createDirectories(target.getParent());
            deleteQuietly(previous);
            if (Files.exists(target)) move(target, previous);
            move(temporary, target);
            deleteQuietly(previous);
            SimulationArtifactResult result = publishPackage(target, safeDate, safeOrder, manifest.assets());
            // 模拟文件、manifest 和占位文件均已进入 ZIP；热备目录不再保留解压目录，避免重复占用空间。
            deleteQuietly(target);
            return result;
        } catch (IOException | RuntimeException error) {
            deleteQuietly(temporary);
            restorePrevious(previous, target);
            if (error instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("生成订单元数据模拟制品失败: orderId=" + orderId, error);
        }
    }

    /** 将隔离目录压缩后提交到热备 ArtifactStore，使查询、详情和下载走真实链路。 */
    private SimulationArtifactResult publishPackage(Path directory, String safeDate, String safeOrder,
                                                    java.util.List<BackupManifest.Asset> assets) {
        Path zip = inside(directory.resolveSibling(directory.getFileName() + ".zip.tmp"));
        String storageKey = "orders/" + safeDate + "/" + safeOrder + ".zip";
        try {
            Files.deleteIfExists(zip);
            try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zip));
                 var paths = Files.walk(directory)) {
                for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                    String entryName = directory.relativize(path).toString().replace('\\', '/');
                    output.putNextEntry(new ZipEntry(entryName));
                    Files.copy(path, output);
                    output.closeEntry();
                }
            }
            String packageSha256 = sha256(zip);
            long packageSize = Files.size(zip);
            try (InputStream input = Files.newInputStream(zip)) { store.put(storageKey, input); }
            Map<String, String> hashes = new LinkedHashMap<>();
            Map<String, String> packageEntryNames = new LinkedHashMap<>();
            String emptySha256 = sha256(new byte[0]);
            for (BackupManifest.Asset asset : assets) {
                hashes.put(asset.assetId(), emptySha256);
                packageEntryNames.put(asset.assetId(), packageEntryName(asset, directory));
            }
            long expectedBytes = assets.stream().mapToLong(BackupManifest.Asset::size).sum();
            return new SimulationArtifactResult(directory, assets.size(), expectedBytes, storageKey,
                    packageSha256, packageSize, Map.copyOf(hashes), Map.copyOf(packageEntryNames));
        } catch (Exception error) {
            throw new IllegalStateException("生成订单模拟压缩包失败: " + directory, error);
        } finally {
            try { Files.deleteIfExists(zip); } catch (IOException ignored) { }
        }
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    /** 计算模拟 ZIP 与数据库证据共用的资产条目名。 */
    private static String packageEntryName(BackupManifest.Asset asset, Path directory) {
        String expected = "files/" + segment(asset.assetId(), "asset") + "-" + segment(asset.fileName(), "asset");
        // 模拟目录中实际生成的文件名已由 uniqueName 处理；优先从目录中按资产前缀解析实际名称。
        Path files = directory.resolve("files");
        try (var paths = Files.list(files)) {
            return paths.filter(Files::isRegularFile)
                    .map(files::relativize)
                    .map(Path::toString)
                    .filter(name -> name.startsWith(segment(asset.assetId(), "asset") + "-"))
                    .sorted().findFirst().map(name -> "files/" + name.replace('\\', '/')).orElse(expected);
        } catch (IOException ignored) {
            return expected;
        }
    }

    private static String sha256(byte[] value) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private Path inside(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) throw new IllegalArgumentException("模拟制品路径越界");
        return normalized;
    }

    private static String segment(String raw, String fallback) {
        String value = raw == null ? "" : raw.replace('\\', '_').replace('/', '_')
                .replaceAll("[<>:\"|?*\\p{Cntrl}]", "_").replaceAll("[. ]+$", "").trim();
        if (value.isBlank() || value.equals(".") || value.equals("..")) value = fallback;
        String stem = value.contains(".") ? value.substring(0, value.indexOf('.')) : value;
        if (WINDOWS_RESERVED.contains(stem.toUpperCase(Locale.ROOT))) value = "_" + value;
        return value.length() > 120 ? value.substring(0, 120) : value;
    }

    private static String uniqueName(String base, String assetId, Set<String> names) {
        if (names.add(base.toLowerCase(Locale.ROOT))) return base;
        String safeId = segment(assetId, "duplicate");
        if (safeId.length() > 40) safeId = safeId.substring(0, 40);
        String suffix = "-" + safeId;
        String candidate = truncateForSuffix(base, suffix) + suffix;
        int number = 2;
        while (!names.add(candidate.toLowerCase(Locale.ROOT))) {
            String numberedSuffix = suffix + "-" + number++;
            candidate = truncateForSuffix(base, numberedSuffix) + numberedSuffix;
        }
        return candidate;
    }

    private static String truncateForSuffix(String base, String suffix) {
        int prefixLength = Math.max(1, 120 - suffix.length());
        return base.length() > prefixLength ? base.substring(0, prefixLength) : base;
    }

    private static boolean isComplete(Path target) {
        return Files.isDirectory(target)
                && Files.isRegularFile(target.resolve("manifest.json"))
                && Files.isRegularFile(target.resolve("_simulation.json"));
    }

    private static void restorePrevious(Path previous, Path target) {
        if (!Files.exists(previous) || Files.exists(target)) return;
        try { move(previous, target); }
        catch (IOException restoreError) {
            throw new IllegalStateException("模拟制品替换失败，且旧制品回滚失败: " + previous, restoreError);
        }
    }

    private static void recoverInterruptedReplacement(Path previous, Path target) {
        if (Files.exists(target) || !isComplete(previous)) return;
        try { move(previous, target); }
        catch (IOException error) {
            throw new IllegalStateException("检测到中断的模拟制品替换，但旧制品恢复失败: " + previous, error);
        }
    }

    /** 预签名地址是临时访问凭据；本地模拟清单只保留“存在过地址”的标识。 */
    private static BackupManifest redactedManifest(BackupManifest manifest) {
        java.util.List<BackupManifest.Asset> assets = manifest.assets().stream().map(asset ->
                new BackupManifest.Asset(asset.assetId(), asset.fileName(), asset.type(), asset.size(),
                        redactSource(asset.sourceKey()), asset.sha256(), asset.platform(), asset.bucket(),
                        asset.objectKey(), asset.versionIdentity(), asset.sourceFileId(), asset.relations())).toList();
        return new BackupManifest(manifest.orderId(), manifest.orderCreatedAt(), assets,
                manifest.manifestRevision(), manifest.manifestSha256(), manifest.relationSetHash(),
                manifest.orderRaw(), manifest.orderDisplay());
    }

    private static String redactSource(String sourceKey) {
        if (sourceKey == null) return null;
        String lower = sourceKey.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://")
                ? "[PRESIGNED_URL_REDACTED]" : sourceKey;
    }

    private static void move(Path source, Path target) throws IOException {
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException ignored) { Files.move(source, target); }
    }

    private static void deleteQuietly(Path path) {
        try { if (Files.exists(path)) deleteTree(path); } catch (IOException ignored) { }
    }

    private static void deleteTree(Path path) throws IOException {
        try (var stream = Files.walk(path)) {
            for (Path item : stream.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(item);
        }
    }

    private static String nullSafe(String value) { return value == null ? "" : value; }
}
