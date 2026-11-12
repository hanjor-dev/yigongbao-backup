package com.yigongbao.backup.backup;

import com.yigongbao.backup.storage.api.VolumeArtifactStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/** 可靠清理线上拒绝的竞争失败订单包；领取和终态更新均由数据库条件更新隔离多实例。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SupersededBackupStageCleanupService {
    private static final int BATCH_SIZE = 50;

    private final BackupStageMapper stages;
    private final VolumeArtifactStore store;

    @Scheduled(fixedDelay = 60_000L, initialDelay = 30_000L)
    public void scheduledCleanup() {
        cleanupBatch();
    }

    int cleanupBatch() {
        int cleaned = 0;
        for (BackupStageEntity stage : stages.findCleanupCandidates(BATCH_SIZE)) {
            if (stage.getId() == null || stages.claimCleanup(stage.getId()) != 1) continue;
            try {
                if (stage.getStorageVolumeId() == null || stage.getStorageVolumeId() < 1
                        || stage.getStorageKey() == null || stage.getStorageKey().isBlank()) {
                    throw new IllegalStateException("暂存制品缺少存储卷或存储键");
                }
                store.delete(stage.getStorageVolumeId(), stage.getStorageKey());
                if (stages.completeCleanup(stage.getId()) != 1) {
                    throw new IllegalStateException("清理完成状态已被其他实例修改");
                }
                cleaned++;
                log.info("竞争失败备份包已清理｜暂存ID={}｜存储卷ID={}｜存储键={}",
                        stage.getId(), stage.getStorageVolumeId(), stage.getStorageKey());
            } catch (Exception failure) {
                int retries = stage.getCleanupRetryCount() == null ? 0 : stage.getCleanupRetryCount();
                long delayMinutes = Math.min(60L, 1L << Math.min(retries, 6));
                String message = safeMessage(failure);
                stages.failCleanup(stage.getId(), message, LocalDateTime.now().plusMinutes(delayMinutes));
                log.warn("竞争失败备份包清理失败，将重试｜暂存ID={}｜重试延迟={}分钟｜原因={}",
                        stage.getId(), delayMinutes, message);
            }
        }
        return cleaned;
    }

    private static String safeMessage(Throwable failure) {
        String message = failure == null || failure.getMessage() == null
                ? "未知清理失败" : failure.getClass().getSimpleName() + ": " + failure.getMessage();
        return message.length() <= 1024 ? message : message.substring(0, 1024);
    }
}
