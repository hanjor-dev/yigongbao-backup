package com.yigongbao.backup.backup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yigongbao.backup.storage.api.ArtifactStore;
import com.yigongbao.backup.storage.api.LegacyArtifactStoreVolumeAdapter;
import com.yigongbao.backup.storage.api.VolumeArtifactStore;
import lombok.extern.slf4j.Slf4j;
import com.yigongbao.backup.common.BusinessLogFormatter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/** 订单级备份：所有资产成功下载后才提交订单包，否则清理暂存目录并标记整单失败。 */
@Slf4j
public final class OrderBackupPipeline {
    private static final long PROGRESS_LOG_BYTES = 8L * 1024 * 1024;
    private final OnlineManifestClient manifests;
    private final CosObjectClient cos;
    private final ArtifactStore store;
    private final VolumeArtifactStore volumeStore;
    private final ObjectMapper mapper;

    public OrderBackupPipeline(OnlineManifestClient manifests, CosObjectClient cos,
                               ArtifactStore store, ObjectMapper mapper) {
        this.manifests = manifests;
        this.cos = cos;
        this.store = store;
        this.volumeStore = null;
        this.mapper = mapper;
    }

    /** 多卷模式构造器；订单任务必须在创建时提供并固化目标卷。 */
    public OrderBackupPipeline(OnlineManifestClient manifests, CosObjectClient cos,
                               VolumeArtifactStore volumeStore, ObjectMapper mapper) {
        this.manifests = manifests;
        this.cos = cos;
        this.store = null;
        this.volumeStore = volumeStore;
        this.mapper = mapper;
    }

    /** 下载并原子提交单个订单；任一资产失败均不保留订单包。 */
    public void backup(String orderId) { backup(orderId, manifests.fetch(orderId)); }

    /** 使用已读取的同一份清单完成下载，避免清单读取与文件下载之间出现版本漂移。 */
    public void backup(String orderId, BackupManifest manifest) { backupWithResult(orderId, manifest); }

    /** 返回订单包的稳定存储键和摘要，供本地制品落库及线上凭证使用。 */
    public Result backupWithResult(String orderId, BackupManifest manifest) {
        return backupWithResult(orderId, manifest, AssetProgressListener.NOOP);
    }

    /** 在真实的逐文件下载边界报告进度，确保任务证据准确指向当前资产。 */
    public Result backupWithResult(String orderId, BackupManifest manifest, AssetProgressListener listener) {
        return backupWithResult(orderId, manifest, listener, () -> { });
    }

    /** 提交前执行租约/fencing 校验，阻止已失去所有权的旧执行器发布制品。 */
    public Result backupWithResult(String orderId, BackupManifest manifest, AssetProgressListener listener,
                                   Runnable beforeCommit) {
        return backupWithResult(orderId, manifest, defaultBackupDate(manifest), orderId, listener, beforeCommit);
    }

    /** 按备份任务日期和订单业务单号生成稳定的订单包存储键。 */
    public Result backupWithResult(String orderId, BackupManifest manifest, String backupDate, String orderNo,
                                   AssetProgressListener listener, Runnable beforeCommit) {
        return backupWithResult(orderId, manifest, backupDate, orderNo, listener, beforeCommit, null);
    }

    /** 按已固化的目标存储卷写入订单 ZIP，避免任务运行期间跟随默认热备目录漂移。 */
    public Result backupWithResult(String orderId, BackupManifest manifest, String backupDate, String orderNo,
                                   AssetProgressListener listener, Runnable beforeCommit, Long storageVolumeId) {
        return backupWithResult(orderId, manifest, backupDate, orderNo, listener, beforeCommit,
                storageVolumeId, null);
    }

    /**
     * 使用任务级不可变作用域生成物理存储键。不同任务即使处理同一订单也不能覆盖彼此制品；
     * 同一任务重试使用相同作用域，便于从暂存状态幂等恢复。
     */
    public Result backupWithResult(String orderId, BackupManifest manifest, String backupDate, String orderNo,
                                   AssetProgressListener listener, Runnable beforeCommit, Long storageVolumeId,
                                   String storageScope) {
        if (manifest == null || manifest.assets() == null) throw new IllegalStateException("订单清单不存在");
        if (volumeStore != null && (storageVolumeId == null || storageVolumeId < 1)
                && !(volumeStore instanceof LegacyArtifactStoreVolumeAdapter)) {
            throw new IllegalArgumentException("多卷订单备份必须指定目标存储卷");
        }
        AssetProgressListener progressListener = listener == null ? AssetProgressListener.NOOP : listener;
        long startedAt = System.nanoTime();
        long totalBytes = manifest.assets().stream().mapToLong(BackupManifest.Asset::size).sum();
        String normalizedDate = normalizeBackupDate(backupDate);
        String safeOrderNo = safeOrderPart(orderNo, orderId);
        Path staging;
        try {
            staging = volumeStore == null
                    ? store.root().resolve(".staging").resolve(orderId + "-" + System.nanoTime())
                    : volumeStore.resolve(storageVolumeId, ".staging/" + orderId + "-" + System.nanoTime());
        } catch (IOException exception) {
            throw new IllegalStateException("无法创建订单备份暂存目录", exception);
        }
        log.info("订单备份流水线开始｜订单ID={}｜文件数量={}｜文件总容量={}",
                orderId, manifest.assets().size(), BusinessLogFormatter.bytes(totalBytes));
        try {
            Files.createDirectories(staging);
            Path data = staging.resolve("files");
            Files.createDirectories(data);
            java.util.Set<String> identities = new java.util.HashSet<>();
            java.util.Map<String, String> downloadedHashes = new java.util.LinkedHashMap<>();
            java.util.Map<String, String> packageEntryNames = new java.util.LinkedHashMap<>();
            int assetIndex = 0;
            for (BackupManifest.Asset asset : manifest.assets()) {
                assetIndex++;
                validateAsset(asset, identities);
                String entryName = "files/" + asset.assetId() + "-" + safe(asset.fileName());
                packageEntryNames.put(asset.assetId(), entryName);
                Path target = data.resolve(entryName.substring("files/".length()));
                long fileStartedAt = System.nanoTime();
                log.info("文件下载开始｜订单ID={}｜文件进度={}/{}｜文件ID={}｜业务类型={}｜文件名={}｜文件容量={}",
                        orderId, assetIndex, manifest.assets().size(), asset.assetId(), asset.type(),
                        safe(asset.fileName()), BusinessLogFormatter.bytes(asset.size()));
                progressListener.started(asset);
                try (InputStream in = cos.download(asset.sourceKey());
                     OutputStream out = Files.newOutputStream(target)) {
                    if (in == null) throw new IOException("COS对象不存在");
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    byte[] buffer = new byte[8192];
                    long copied = 0;
                    long nextReport = PROGRESS_LOG_BYTES;
                    int count;
                    while ((count = in.read(buffer)) != -1) {
                        copied += count;
                        if (copied > asset.size()) throw new IOException("资产实际大小超过清单: " + asset.assetId());
                        digest.update(buffer, 0, count);
                        out.write(buffer, 0, count);
                        if (copied >= nextReport || copied == asset.size()) {
                            progressListener.progress(asset, copied);
                            log.info("文件下载进度: orderId={}, assetId={}, bytes={}/{}, percent={}",
                                    orderId, asset.assetId(), copied, asset.size(), percent(copied, asset.size()));
                            nextReport = copied + PROGRESS_LOG_BYTES;
                        }
                    }
                    verifyDownloadedAsset(asset, copied, digest, downloadedHashes);
                    progressListener.succeeded(asset);
                    log.info("文件下载完成｜订单ID={}｜文件ID={}｜文件容量={}｜执行耗时={}",
                            orderId, asset.assetId(), BusinessLogFormatter.bytes(copied),
                            BusinessLogFormatter.duration(elapsedMillis(fileStartedAt)));
                } catch (Exception failure) {
                    progressListener.failed(asset, failure);
                    log.error("文件下载失败｜订单ID={}｜文件ID={}｜业务类型={}｜文件名={}｜执行耗时={}｜异常类型={}｜异常原因={}",
                            orderId, asset.assetId(), asset.type(), safe(asset.fileName()),
                            BusinessLogFormatter.duration(elapsedMillis(fileStartedAt)),
                            failure.getClass().getSimpleName(), safeError(failure), safeThrowable(failure));
                    throw failure;
                }
            }

            Path meta = staging.resolve("manifest.json");
            mapper.writeValue(meta.toFile(), manifest);
            Path zip = staging.resolve(safeOrderPart(orderId, "order") + ".zip");
            long compressStartedAt = System.nanoTime();
            log.info("订单包压缩开始: orderId={}, assetCount={}", orderId, manifest.assets().size());
            try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
                // 与持久化的 packageEntryName 保持一致；关系缺口不进入 files/。
                zipTree(data, out, staging);
                add(out, meta, "manifest.json");
            }
            log.info("订单包压缩完成｜订单ID={}｜压缩包容量={}｜执行耗时={}",
                    orderId, BusinessLogFormatter.bytes(Files.size(zip)),
                    BusinessLogFormatter.duration(elapsedMillis(compressStartedAt)));

            log.info("订单清单一致性确认开始: orderId={}, manifestRevision={}", orderId, manifest.manifestRevision());
            manifests.confirm(orderId, manifest);
            beforeCommit.run();
            log.info("订单清单一致性确认完成: orderId={}", orderId);

            String scopeSuffix = storageScope == null || storageScope.isBlank()
                    ? "" : "-" + safeOrderPart(storageScope, "scope");
            String storageKey = "orders/" + normalizedDate + "/" + safeOrderNo + "-"
                    + safeOrderPart(orderId, "order") + scopeSuffix + ".zip";
            String packageSha256 = sha256(zip);
            long packageSize = Files.size(zip);
            try (InputStream in = Files.newInputStream(zip)) {
                if (volumeStore == null) store.put(storageKey, in);
                else volumeStore.put(storageVolumeId, storageKey, in);
            }
            log.info("订单备份制品提交完成｜订单ID={}｜本地存储键={}｜压缩包容量={}｜包摘要={}｜总执行耗时={}",
                    orderId, storageKey, BusinessLogFormatter.bytes(packageSize),
                    BusinessLogFormatter.shortHash(packageSha256),
                    BusinessLogFormatter.duration(elapsedMillis(startedAt)));
            return new Result(storageKey, packageSha256, packageSize,
                    java.util.Map.copyOf(downloadedHashes), java.util.Map.copyOf(packageEntryNames));
        } catch (Exception failure) {
            log.error("订单备份流水线失败并回滚: orderId={}, durationMs={}, errorType={}, error={}",
                    orderId, elapsedMillis(startedAt), failure.getClass().getSimpleName(), safeError(failure));
            if (failure instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("订单备份失败，整单回滚: " + orderId, failure);
        } finally {
            deleteTree(staging);
            log.debug("订单备份暂存目录清理完成: orderId={}", orderId);
        }
    }

    private static String defaultBackupDate(BackupManifest manifest) {
        return LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
    }

    private static String normalizeBackupDate(String value) {
        if (value == null || !value.matches("\\d{8}")) {
            throw new IllegalArgumentException("备份日期必须为yyyyMMdd格式");
        }
        return value;
    }

    private static String safeOrderPart(String value, String fallback) {
        String source = value == null || value.isBlank() ? fallback : value.trim();
        String sanitized = source.replaceAll("[^A-Za-z0-9._-]", "_");
        while (sanitized.contains("..")) sanitized = sanitized.replace("..", "_");
        if (sanitized.isBlank() || sanitized.equals(".") || sanitized.equals("..")) sanitized = fallback;
        return sanitized.length() > 100 ? sanitized.substring(0, 100) : sanitized;
    }

    private static void validateAsset(BackupManifest.Asset asset, java.util.Set<String> identities) {
        if (asset.size() < 0 || asset.assetId() == null || !identities.add(asset.assetId())) {
            throw new IllegalStateException("资产身份重复或大小非法");
        }
    }

    private static void verifyDownloadedAsset(BackupManifest.Asset asset, long copied,
                                              MessageDigest digest,
                                              java.util.Map<String, String> downloadedHashes) throws IOException {
        if (copied != asset.size()) throw new IOException("资产大小与清单不一致: " + asset.assetId());
        String downloadedHash = HexFormat.of().formatHex(digest.digest());
        if (asset.sha256() != null && !asset.sha256().equalsIgnoreCase(downloadedHash)) {
            throw new IOException("资产摘要与清单不一致: " + asset.assetId());
        }
        downloadedHashes.put(asset.assetId(), downloadedHash);
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static long elapsedMillis(long startedAt) { return (System.nanoTime() - startedAt) / 1_000_000L; }
    private static long percent(long done, long total) { return total <= 0 ? 100 : Math.min(100, done * 100 / total); }

    /** 清理可能包含预签名 URL 或查询签名的异常文本后再写日志。 */
    static String safeError(Throwable error) {
        String message = error == null || error.getMessage() == null ? "无错误详情" : error.getMessage();
        return message.replaceAll("(?i)https?://\\S+", "[URL_REDACTED]")
                .replaceAll("(?i)(signature|secret|token|credential|authorization)\\s*[=:]\\s*[^,;]+", "$1=[REDACTED]");
    }


    private static Throwable safeThrowable(Throwable error) {
        RuntimeException sanitized = new RuntimeException(error.getClass().getSimpleName()
                + ": " + safeError(error));
        sanitized.setStackTrace(error.getStackTrace());
        return sanitized;
    }

    /** 订单包内文件名必须约束在单一文件名范围内，阻断路径穿越。 */
    private static String safe(String name) {
        String normalized = Paths.get(name).getFileName().toString();
        if (normalized.isBlank()) throw new IllegalArgumentException("文件名为空");
        return normalized;
    }

    /** 递归写入订单包目录。 */
    private static void zipTree(Path directory, ZipOutputStream out, Path root) throws IOException {
        try (var paths = Files.list(directory)) {
            for (Path path : paths.toList()) {
                if (Files.isDirectory(path)) zipTree(path, out, root);
                else add(out, path, root.relativize(path).toString().replace('\\', '/'));
            }
        }
    }

    /** 写入一个压缩条目。 */
    private static void add(ZipOutputStream out, Path path, String name) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        // 包摘要参与线上回执幂等校验；禁止把当前时间写进 ZIP 元数据，
        // 确保同一清单和相同文件内容在重试时生成完全一致的订单包。
        entry.setTime(0L);
        out.putNextEntry(entry);
        Files.copy(path, out);
        out.closeEntry();
    }

    /** 清理订单暂存目录，避免中间文件被误认为已备份。 */
    private static void deleteTree(Path path) {
        if (!Files.exists(path)) return;
        try (var paths = Files.walk(path)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(item -> {
                try { Files.deleteIfExists(item); }
                catch (IOException ignored) { log.warn("订单备份暂存文件清理失败: path={}", item.getFileName()); }
            });
        } catch (IOException failure) {
            log.warn("订单备份暂存目录遍历失败: path={}, error={}", path.getFileName(), safeError(failure));
        }
    }

    public record Result(String storageKey, String packageSha256, long packageSize,
                         java.util.Map<String, String> downloadedHashes,
                         java.util.Map<String, String> packageEntryNames) {
        /** 兼容旧测试和历史调用方：按约定条目名回退。 */
        public Result(String storageKey, String packageSha256, long packageSize,
                      java.util.Map<String, String> downloadedHashes) {
            this(storageKey, packageSha256, packageSize, downloadedHashes, java.util.Map.of());
        }
    }

    /** 订单备份过程的文件级证据回调。 */
    public interface AssetProgressListener {
        AssetProgressListener NOOP = new AssetProgressListener() { };
        default void started(BackupManifest.Asset asset) { }
        default void progress(BackupManifest.Asset asset, long downloadedBytes) { }
        default void succeeded(BackupManifest.Asset asset) { }
        default void failed(BackupManifest.Asset asset, Throwable failure) { }
    }
}
