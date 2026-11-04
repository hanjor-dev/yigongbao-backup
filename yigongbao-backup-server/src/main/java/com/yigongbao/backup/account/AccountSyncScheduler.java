package com.yigongbao.backup.account;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.yigongbao.backup.config.RuntimeConfigService;
import lombok.extern.slf4j.Slf4j;
import java.time.LocalDateTime;

/** 按数据库中的最近成功时间和配置周期触发账号同步。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AccountSyncScheduler {
    private final AccountSyncCoordinator coordinator;
    private final AccountSyncCheckpointService checkpoints;
    private final RuntimeConfigService configs;

    /** 每分钟观察一次配置，失败任务会在下一分钟重试最后未提交页。 */
    @Scheduled(fixedDelay = 60000)
    public void sync() {
        try {
            long delayMinutes = configs.getLong("account.sync-delay-minutes", 30L, 1L);
            AccountSyncCheckpointService.Status status = checkpoints.status();
            LocalDateTime lastSuccess = status.lastSuccessAt();
            if (lastSuccess != null && lastSuccess.plusMinutes(delayMinutes).isAfter(LocalDateTime.now())) return;
            AccountSyncCoordinator.Result result = coordinator.synchronize(false);
            if ("DISABLED".equals(result.status())) log.debug("账号同步未执行: {}", result.message());
        } catch (RuntimeException exception) {
            log.error("账号同步调度失败", exception);
        }
    }
}
