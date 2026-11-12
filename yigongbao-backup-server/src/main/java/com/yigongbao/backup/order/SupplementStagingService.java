package com.yigongbao.backup.order;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

/** 补传上传体暂存在系统临时区，不能在容量预留前写入活动备份卷。 */
@Service
@Slf4j
public class SupplementStagingService {
    private final Path root;

    public SupplementStagingService() {
        this(Path.of(System.getProperty("java.io.tmpdir"), "yigongbao-backup", "supplements"));
    }

    SupplementStagingService(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Path create() throws IOException {
        Files.createDirectories(root);
        return Files.createTempFile(root, "upload-", ".part");
    }

    public void delete(Path staged) {
        if (staged == null) return;
        Path normalized = staged.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) return;
        try { Files.deleteIfExists(normalized); }
        catch (IOException failure) { log.warn("补传暂存文件清理失败｜路径={}｜原因={}", normalized, failure.getMessage()); }
    }

    @Scheduled(fixedDelay = 3_600_000L, initialDelay = 3_600_000L)
    public void cleanupExpired() {
        cleanupBefore(Instant.now().minus(Duration.ofHours(24)));
    }

    int cleanupBefore(Instant cutoff) {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return 0;
        int deleted = 0;
        try (var paths = Files.list(root)) {
            for (Path path : paths.toList()) {
                String name = path.getFileName().toString();
                if (!name.startsWith("upload-") || !name.endsWith(".part")
                        || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
                try {
                    if (Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(cutoff)
                            && Files.deleteIfExists(path)) deleted++;
                } catch (IOException failure) {
                    log.warn("过期补传暂存文件清理失败｜路径={}｜原因={}", path, failure.getMessage());
                }
            }
        } catch (IOException failure) {
            log.warn("扫描补传暂存目录失败｜目录={}｜原因={}", root, failure.getMessage());
        }
        return deleted;
    }
}
