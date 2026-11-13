package com.yigongbao.backup.backup;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yigongbao.backup.job.JobEntity;
import com.yigongbao.backup.job.JobMapper;
import com.yigongbao.backup.storage.api.VolumeArtifactStore;
import com.yigongbao.backup.storage.volume.StorageVolumeEntity;
import com.yigongbao.backup.storage.volume.StorageVolumeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 清理任务异常退出后已落盘、但从未进入任何持久化台账的订单包。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UnregisteredBackupArtifactCleanupService {
    private static final Pattern JOB_ID = Pattern.compile("(?:^|-)job-(\\d+)(?:-|$)");
    private static final Set<String> RETRYABLE_JOB_STATUSES = Set.of(
            "CREATED", "RUNNING", "RETRYING_FAILED_ORDERS", "PAUSED");
    private static final Duration UNKNOWN_FILE_GRACE = Duration.ofHours(24);

    private final StorageVolumeMapper volumes;
    private final UnregisteredBackupArtifactMapper references;
    private final JobMapper jobs;
    private final VolumeArtifactStore store;

    @Scheduled(fixedDelay = 60_000L, initialDelay = 30_000L)
    public void scheduledCleanup() {
        cleanupBatch();
    }

    int cleanupBatch() {
        int cleaned = 0;
        List<StorageVolumeEntity> candidates = volumes.selectList(Wrappers.<StorageVolumeEntity>lambdaQuery()
                .eq(StorageVolumeEntity::getTier, "HOT")
                .eq(StorageVolumeEntity::getMountStatus, "ONLINE")
                .in(StorageVolumeEntity::getOperationStatus, "IDLE", "REPAIR_REQUIRED")
                .eq(StorageVolumeEntity::getIsDeleted, 0));
        for (StorageVolumeEntity volume : candidates) {
            if (volume.getId() == null) continue;
            Set<String> protectedKeys = new HashSet<>(references.findProtectedStorageKeys(volume.getId()));
            try {
                Path orders = store.resolve(volume.getId(), "orders");
                if (!Files.exists(orders, LinkOption.NOFOLLOW_LINKS)) continue;
                try (var files = Files.walk(orders)) {
                    for (Path file : files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).toList()) {
                        String key = "orders/" + orders.relativize(file).toString().replace('\\', '/');
                        if (protectedKeys.contains(key) || !isSafeToDelete(key, file)) continue;
                        Files.deleteIfExists(file);
                        cleaned++;
                        log.warn("已清理未登记备份包｜存储卷ID={}｜存储键={}", volume.getId(), key);
                    }
                }
            } catch (Exception failure) {
                log.warn("未登记备份包巡检失败｜存储卷ID={}｜异常原因={}",
                        volume.getId(), safeMessage(failure));
            }
        }
        return cleaned;
    }

    private boolean isSafeToDelete(String storageKey, Path file) throws Exception {
        Matcher matcher = JOB_ID.matcher(storageKey);
        if (matcher.find()) {
            JobEntity owner = jobs.selectById(Long.parseLong(matcher.group(1)));
            return owner == null || !RETRYABLE_JOB_STATUSES.contains(owner.getStatus());
        }
        Instant modified = Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toInstant();
        return modified.plus(UNKNOWN_FILE_GRACE).isBefore(Instant.now());
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
