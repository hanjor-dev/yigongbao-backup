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

/** 扫描管理员复制到导入区的冷备压缩包，按订单选择性恢复到热备目录。 */
public final class ColdImportService {
    private static final long DEFAULT_MAX_IMPORT_BYTES = 4L * 1024 * 1024 * 1024 * 1024;
    private static final int MAX_ENTRIES = 200_000;

    private final Path importRoot;
    private final ArtifactStore hot;
    private final long maxImportBytes;

    public ColdImportService(Path importRoot, ArtifactStore hot) {
        this(importRoot, hot, DEFAULT_MAX_IMPORT_BYTES);
    }

    public ColdImportService(Path importRoot, ArtifactStore hot, long maxImportBytes) {
        if (importRoot == null || hot == null || maxImportBytes <= 0) {
            throw new IllegalArgumentException("冷备导入参数非法");
        }
        this.importRoot = importRoot.toAbsolutePath().normalize();
        this.hot = hot;
        this.maxImportBytes = maxImportBytes;
    }

    /** 扫描管理员放入冷备导入区的压缩包。 */
    public List<Path> scan() throws IOException {
        if (!Files.exists(importRoot)) {
            return List.of();
        }
        try (var paths = Files.list(importRoot)) {
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
        Path archive = importRoot.resolve(batchZipName).normalize();
        if (!archive.startsWith(importRoot) || !Files.isRegularFile(archive)) {
            throw new IllegalArgumentException("导入包不存在");
        }

        Path staging = hot.root().resolve(".cold-import-staging").resolve(UUID.randomUUID().toString());
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
                    long written = copyBounded(in, staged, maxImportBytes - totalBytes);
                    totalBytes += written;
                    if (totalBytes > maxImportBytes) {
                        throw new IOException("冷备导入解压总量超过配置限制");
                    }
                    String fileName = Path.of(name).getFileName().toString();
                    Path target = hot.root().resolve("orders").resolve(order).resolve(fileName).normalize();
                    if (!target.startsWith(hot.root())) {
                        throw new IOException("目标路径非法");
                    }
                    pending.add(new PendingImport(staged, target));
                }
            }
            if (!completeMarker) {
                throw new IOException("冷备归档缺少 _COMPLETE 完成标记");
            }
            for (PendingImport item : pending) {
                Files.createDirectories(item.target().getParent());
                try {
                    Files.move(item.staged(), item.target(), StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                    Files.move(item.staged(), item.target(), StandardCopyOption.REPLACE_EXISTING);
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
                if (total > maxImportBytes) throw new IOException("冷备导入解压总量超过配置限制");
            }
        }
        return Math.max(1L, total);
    }

    private Path resolveArchive(String batchZipName, Set<String> orderIds) {
        if (batchZipName == null || batchZipName.isBlank()
                || Path.of(batchZipName).getNameCount() != 1
                || !batchZipName.toLowerCase().endsWith(".zip")) throw new IllegalArgumentException("导入包名称非法");
        if (orderIds != null) for (String orderId : orderIds) if (orderId == null || !orderId.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("订单号非法");
        Path archive = importRoot.resolve(batchZipName).normalize();
        if (!archive.startsWith(importRoot) || !Files.isRegularFile(archive)) throw new IllegalArgumentException("导入包不存在");
        return archive;
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

    /** 从标准化压缩路径提取订单号。 */
    private static String orderOf(String name) {
        if (!name.startsWith("orders/")) {
            return null;
        }
        String[] parts = name.split("/");
        return parts.length >= 3 && parts[1].matches("[A-Za-z0-9_-]{1,64}") ? parts[1] : null;
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
