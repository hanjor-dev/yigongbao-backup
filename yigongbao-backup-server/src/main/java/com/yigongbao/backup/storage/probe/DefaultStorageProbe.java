package com.yigongbao.backup.storage.probe;

import com.yigongbao.backup.storage.api.StorageProbe;
import java.nio.file.Files;
import java.nio.file.Path;

/** 使用 Windows/JVM 文件系统 API 的默认容量探测器。 */
public final class DefaultStorageProbe implements StorageProbe {
    public StorageSnapshot probe(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        try { long total = normalized.toFile().getTotalSpace(); return new StorageSnapshot(normalized.toString(), normalized, total, normalized.toFile().getUsableSpace(), total > 0); }
        catch (RuntimeException ex) { return new StorageSnapshot(normalized.toString(), normalized, -1, -1, false); }
    }
}
