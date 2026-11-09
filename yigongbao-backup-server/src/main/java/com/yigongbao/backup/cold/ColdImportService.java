package com.yigongbao.backup.cold;

import com.yigongbao.backup.storage.api.ArtifactStore;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipFile;
import com.yigongbao.backup.config.RuntimeConfigService;
import com.yigongbao.backup.storage.api.VolumeArtifactStore;
import com.yigongbao.backup.storage.volume.ActiveVolumeSelectionService;
import com.yigongbao.backup.storage.volume.StorageVolumeEntity;
import com.yigongbao.backup.storage.volume.StorageVolumeMapper;
import com.yigongbao.backup.storage.location.ArtifactCopyEntity;
import com.yigongbao.backup.storage.location.ArtifactCopyMapper;
import com.yigongbao.backup.order.OrderPackageEntity;
import com.yigongbao.backup.order.OrderPackageMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

/** 扫描管理员复制到导入区的冷备压缩包，按订单选择性恢复到热备目录。 */
public final class ColdImportService {
    private static final long DEFAULT_MAX_IMPORT_BYTES = 4L * 1024 * 1024 * 1024 * 1024;
    private static final int MAX_ENTRIES = 200_000;

    private final Path importRoot;
    private final ArtifactStore hot;
    private final long maxImportBytes;
    private final RuntimeConfigService runtimeConfig;
    private final StorageVolumeMapper volumeMapper;
    private final ActiveVolumeSelectionService activeVolumes;
    private final VolumeArtifactStore volumeStore;
    private final ArtifactCopyMapper copies;
    private final OrderPackageMapper packages;

    public ColdImportService(Path importRoot, ArtifactStore hot) {
        this(importRoot, hot, DEFAULT_MAX_IMPORT_BYTES, null);
    }

    public ColdImportService(Path importRoot, ArtifactStore hot, long maxImportBytes) {
        this(importRoot, hot, maxImportBytes, null);
    }

    /** 使用数据库中的冷备导入上限；导入目录本身仍属于基础设施路径。 */
    public ColdImportService(Path importRoot, ArtifactStore hot, RuntimeConfigService runtimeConfig) {
        this(importRoot, hot, DEFAULT_MAX_IMPORT_BYTES, runtimeConfig);
    }

    private ColdImportService(Path importRoot, ArtifactStore hot, long maxImportBytes, RuntimeConfigService runtimeConfig) {
        if (importRoot == null || hot == null || maxImportBytes <= 0) {
            throw new IllegalArgumentException("冷备导入参数非法");
        }
        this.importRoot = importRoot.toAbsolutePath().normalize();
        this.hot = hot;
        this.maxImportBytes = maxImportBytes;
        this.runtimeConfig = runtimeConfig;
        this.volumeMapper = null;
        this.activeVolumes = null;
        this.volumeStore = null;
        this.copies = null;
        this.packages = null;
    }

    /** Production constructor: IMPORT and HOT roots come from managed volume records. */
    public ColdImportService(StorageVolumeMapper volumeMapper, ActiveVolumeSelectionService activeVolumes,
                             VolumeArtifactStore volumeStore, ArtifactCopyMapper copies, OrderPackageMapper packages,
                             long maxImportBytes, RuntimeConfigService runtimeConfig) {
        this.importRoot = null; this.hot = null; this.maxImportBytes = maxImportBytes;
        this.runtimeConfig = runtimeConfig; this.volumeMapper = volumeMapper; this.activeVolumes = activeVolumes; this.volumeStore = volumeStore;
        this.copies = copies; this.packages = packages;
    }

    /** 扫描管理员放入冷备导入区的压缩包。 */
    public List<Path> scan() throws IOException {
        Path root = importRoot();
        if (!Files.exists(root)) {
            return List.of();
        }
        try (var paths = Files.list(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".zip"))
                    .sorted()
                    .toList();
        }
    }

    /** 按订单筛选解压并原子移动到热备目录；扫描失败时不留下已写入的中间文件。 */
    public int restore(String batchZipName, Set<String> orderIds) throws IOException {
        if (batchZipName == null || batchZipName.isBlank()
                || Path.of(batchZipName).getNameCount() != 1
                || !batchZipName.toLowerCase().endsWith(".zip")) {
            throw new IllegalArgumentException("导入包名称非法");
        }
        if (orderIds != null) {
            for (String orderId : orderIds) {
                if (orderId == null || !orderId.matches("[A-Za-z0-9_-]{1,64}")) {
                    throw new IllegalArgumentException("订单号非法");
                }
            }
        }
        Path root = importRoot();
        Path archive = root.resolve(batchZipName).normalize();
        if (!archive.startsWith(root) || !Files.isRegularFile(archive)) {
            throw new IllegalArgumentException("导入包不存在");
        }

        StorageVolumeEntity destinationVolume = activeVolumes == null ? null : activeVolumes.selectActiveForNewBackup(estimateRestoreBytes(batchZipName, orderIds));
        Path hotRoot = hotRoot();
        Path staging = volumeStore == null ? hotRoot.resolve(".cold-import-staging").resolve(UUID.randomUUID().toString())
                : Files.createTempDirectory("yigongbao-cold-import-");
        List<PendingImport> pending = new ArrayList<>();
        long totalBytes = 0;
        int entries = 0;
        boolean completeMarker = false;
        try {
            Files.createDirectories(staging);
            try (ZipInputStream in = new ZipInputStream(Files.newInputStream(archive))) {
                ZipEntry entry;
                while ((entry = in.getNextEntry()) != null) {
                    if (++entries > MAX_ENTRIES) {
                        throw new IOException("冷备导入条目数量超过限制");
                    }
                    String name = safeEntryName(entry);
                    if (entry.isDirectory()) {
                        continue;
                    }
                    if ("_COMPLETE".equals(name)) {
                        completeMarker = true;
                        continue;
                    }
                    if ("batch-manifest.json".equals(name)) {
                        continue;
                    }
                    String order = orderOf(name);
                    if (order == null || (orderIds != null && !orderIds.isEmpty() && !orderIds.contains(order))) {
                        continue;
                    }
                    Path staged = staging.resolve(Integer.toString(pending.size()) + ".part");
                    long written = copyBounded(in, staged, maxImportBytes() - totalBytes);
                    totalBytes += written;
                    if (totalBytes > maxImportBytes()) {
                        throw new IOException("冷备导入解压总量超过配置限制");
                    }
                    String fileName = Path.of(name).getFileName().toString();
                    Path target = hotRoot.resolve(targetPath(name, order, fileName)).normalize();
                    if (!target.startsWith(hotRoot)) {
                        throw new IOException("目标路径非法");
                    }
                    pending.add(new PendingImport(staged, target));
                }
            }
            if (!completeMarker) {
                throw new IOException("冷备归档缺少 _COMPLETE 完成标记");
            }
            for (PendingImport item : pending) {
                if (volumeStore != null) {
                    String storageKey = hotRoot.relativize(item.target()).toString().replace('\\', '/');
                    try (var input = Files.newInputStream(item.staged())) {
                        volumeStore.put(destinationVolume.getId(), storageKey, input);
                    }
                    recordRestoredCopy(destinationVolume.getId(), storageKey, Files.size(item.staged()));
                } else {
                    Files.createDirectories(item.target().getParent());
                    try {
                        Files.move(item.staged(), item.target(), StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING);
                    } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                        Files.move(item.staged(), item.target(), StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            return pending.size();
        } finally {
            deleteTree(staging);
        }
    }

    /** 在回迁前读取压缩条目元数据，估算将写入热备区的解压空间。 */
    public long estimateRestoreBytes(String batchZipName, Set<String> orderIds) throws IOException {
        Path archive = resolveArchive(batchZipName, orderIds);
        long total = 0L;
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String name = safeEntryName(entry);
                String order = orderOf(name);
                if (order == null || (orderIds != null && !orderIds.isEmpty() && !orderIds.contains(order))) continue;
                long size = entry.getSize();
                if (size < 0 || Long.MAX_VALUE - total < size) throw new IOException("冷备导入空间估算失败");
                total += size;
                if (total > maxImportBytes()) throw new IOException("冷备导入解压总量超过配置限制");
            }
        }
        return Math.max(1L, total);
    }

    private Path resolveArchive(String batchZipName, Set<String> orderIds) {
        if (batchZipName == null || batchZipName.isBlank()
                || Path.of(batchZipName).getNameCount() != 1
                || !batchZipName.toLowerCase().endsWith(".zip")) throw new IllegalArgumentException("导入包名称非法");
        if (orderIds != null) for (String orderId : orderIds) if (orderId == null || !orderId.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("订单号非法");
        Path root = importRoot();
        Path archive = root.resolve(batchZipName).normalize();
        if (!archive.startsWith(root) || !Files.isRegularFile(archive)) throw new IllegalArgumentException("导入包不存在");
        return archive;
    }

    private Path importRoot() {
        if (importRoot != null) return importRoot;
        StorageVolumeEntity volume = volumeMapper.selectOne(Wrappers.<StorageVolumeEntity>lambdaQuery()
                .eq(StorageVolumeEntity::getTier, "IMPORT").eq(StorageVolumeEntity::getMountStatus, "ONLINE")
                .eq(StorageVolumeEntity::getIsDeleted, 0).last("LIMIT 1"));
        if (volume == null) throw new IllegalStateException("没有在线 IMPORT 存储卷");
        return Path.of(volume.getRootPath()).toAbsolutePath().normalize();
    }

    private Path hotRoot() {
        return hot != null ? hot.root() : Path.of(activeVolumes.requireActiveHotVolume().getRootPath()).toAbsolutePath().normalize();
    }

    /** Import job pins the same currently active destination volume for its reservation. */
    public long activeHotVolumeId() {
        if (activeVolumes == null) throw new IllegalStateException("历史导入适配器不支持按卷预留");
        return activeVolumes.requireActiveHotVolume().getId();
    }

    private static String safeEntryName(ZipEntry entry) throws IOException {
        String raw = entry.getName();
        if (raw == null || raw.isBlank() || raw.indexOf('\0') >= 0) {
            throw new IOException("压缩包路径非法");
        }
        String normalized = raw.replace('\\', '/');
        Path path = Path.of(normalized).normalize();
        if (normalized.startsWith("/") || path.isAbsolute() || path.startsWith("..")
                || normalized.contains("../") || normalized.contains("..\\")) {
            throw new IOException("压缩包路径非法");
        }
        return path.toString().replace('\\', '/');
    }

    private static long copyBounded(ZipInputStream in, Path target, long limit) throws IOException {
        long written = 0;
        try (OutputStream out = Files.newOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                written += read;
                if (written > limit) {
                    throw new IOException("冷备导入解压总量超过配置限制");
                }
                out.write(buffer, 0, read);
            }
        }
        return written;
    }

    private long maxImportBytes() {
        return runtimeConfig == null ? maxImportBytes : runtimeConfig.getLong("cold.max-import-bytes", maxImportBytes, 1L);
    }

    /** 仅为已知订单制品补齐热备副本台账，避免为无法关联业务的数据制造孤儿记录。 */
    private void recordRestoredCopy(long volumeId, String storageKey, long size) {
        if (copies == null || packages == null) return;
        OrderPackageEntity pkg = packages.selectOne(Wrappers.<OrderPackageEntity>lambdaQuery()
                .eq(OrderPackageEntity::getStorageKey, storageKey).last("LIMIT 1"));
        if (pkg == null) return;
        ArtifactCopyEntity copy = copies.selectOne(Wrappers.<ArtifactCopyEntity>lambdaQuery()
                .eq(ArtifactCopyEntity::getArtifactType, "ORDER_PACKAGE").eq(ArtifactCopyEntity::getArtifactId, pkg.getId())
                .eq(ArtifactCopyEntity::getStorageVolumeId, volumeId).last("LIMIT 1"));
        if (copy == null) {
            copy = new ArtifactCopyEntity(); copy.setArtifactType("ORDER_PACKAGE"); copy.setArtifactId(pkg.getId());
            copy.setStorageVolumeId(volumeId); copy.setStorageKey(storageKey);
            copy.setState("PRESENT"); copy.setLifecycle("ACTIVE"); copy.setSize(size); copies.insert(copy);
            return;
        }
        copy.setState("PRESENT"); copy.setLifecycle("ACTIVE"); copy.setSize(size); copies.updateById(copy);
    }

    /** 从标准化压缩路径提取订单号。 */
    private static String orderOf(String name) {
        if (!name.startsWith("orders/")) {
            return null;
        }
        String[] parts = name.split("/");
        if (parts.length >= 3 && parts[1].matches("[A-Za-z0-9_-]{1,64}") && !"original".equals(parts[1])) {
            if ("original".equals(parts[2])) return parts[1];
        }
        return null;
    }

    /** 新归档条目携带原始日期目录，导入后恢复到数据库 storage_key 对应位置。 */
    private static Path targetPath(String name, String order, String fileName) {
        String[] parts = name.split("/");
        if (parts.length >= 5 && "orders".equals(parts[0]) && order.equals(parts[1])
                && "original".equals(parts[2]) && parts[3].matches("\\d{8}")) {
            return Path.of(parts[0], parts[3], fileName).normalize();
        }
        return Path.of("orders", order, fileName).normalize();
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 清理失败由后续存储探测和运维告警发现，不覆盖导入主结果。
                }
            });
        } catch (IOException ignored) {
            // 同上。
        }
    }

    private record PendingImport(Path staged, Path target) {
    }
}
