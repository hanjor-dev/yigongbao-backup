package com.yigongbao.backup.job;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 定期回收执行器崩溃或网络中断遗留的过期租约。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ExpiredLeaseRecoveryScheduler {
    private final PersistentJobCoordinator coordinator;

    @Scheduled(fixedDelayString = "${backup.job.recovery-interval-ms:60000}")
    public void recover() {
        try {
            int count = coordinator.recoverExpired();
            if (count > 0) log.info("已回收过期备份任务租约: count={}", count);
        } catch (RuntimeException error) {
            log.error("回收过期备份任务租约失败", error);
        }
    }
}
