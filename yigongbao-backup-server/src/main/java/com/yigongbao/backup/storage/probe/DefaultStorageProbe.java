package com.yigongbao.backup.storage.probe;

import com.yigongbao.backup.storage.api.StorageProbe;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 使用 Windows/JVM 文件系统 API 的默认容量探测器。 */
@Slf4j
public final class DefaultStorageProbe implements StorageProbe {
    public StorageSnapshot probe(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        try {
            if (Files.exists(normalized) && !Files.isDirectory(normalized)) {
                log.warn("热备容量探测失败｜配置路径不是目录｜路径={}", normalized);
                return unknown(normalized);
            }
            if (Files.notExists(normalized)) {
                Files.createDirectories(normalized);
                log.info("热备存储目录初始化完成｜目录={}", normalized);
            }
            long total = normalized.toFile().getTotalSpace();
            long usable = normalized.toFile().getUsableSpace();
            boolean known = total > 0 && usable >= 0;
            if (!known) log.warn("热备容量探测失败｜文件系统未返回有效容量｜目录={}｜总容量={}｜可用容量={}",
                    normalized, total, usable);
            return new StorageSnapshot(normalized.toString(), normalized, total, usable, known);
        } catch (IOException | RuntimeException ex) {
            log.warn("热备容量探测失败｜目录={}｜异常类型={}｜异常原因={}", normalized,
                    ex.getClass().getSimpleName(), ex.getMessage(), ex);
            return unknown(normalized);
        }
    }

    private static StorageSnapshot unknown(Path path) {
        return new StorageSnapshot(path.toString(), path, -1, -1, false);
    }
}
