package com.yigongbao.backup.storage.volume;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Service;

/** 存储卷健康探测服务；探测失败仅更新健康判断，不推断文件是否丢失。 */
@Service
public class StorageVolumeHealthService {
    /** 读取目录可访问性、容量与可写状态。 */
    public HealthSnapshot probe(StorageVolumeEntity volume) {
        Instant startedAt = Instant.now();
        Path root = Path.of(volume.getRootPath());
        try {
            if (!Files.isDirectory(root)) {
                return HealthSnapshot.unavailable("ROOT_NOT_FOUND", "受控存储目录不存在", elapsed(startedAt));
            }
            FileStore store = Files.getFileStore(root);
            long total = store.getTotalSpace();
            long usable = store.getUsableSpace();
            boolean writable = Files.isWritable(root);
            return new HealthSnapshot(true, writable, total, usable, null, null, elapsed(startedAt));
        } catch (IOException | SecurityException exception) {
            return HealthSnapshot.unavailable("STORAGE_PROBE_FAILED", exception.getMessage(), elapsed(startedAt));
        }
    }

    private long elapsed(Instant startedAt) {
        return Duration.between(startedAt, Instant.now()).toMillis();
    }

    /** 供生命周期服务持久化的探测快照。 */
    public record HealthSnapshot(boolean accessible, boolean writable, long totalBytes, long usableBytes,
                                 String errorCode, String errorMessage, long latencyMillis) {
        static HealthSnapshot unavailable(String errorCode, String errorMessage, long latencyMillis) {
            return new HealthSnapshot(false, false, 0L, 0L, errorCode, errorMessage, latencyMillis);
        }
    }
}
