package com.yigongbao.backup.job;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.yigongbao.backup.config.RuntimeConfigService;

/** 定期回收执行器崩溃或网络中断遗留的过期租约。 */
@Component
@Slf4j
public class ExpiredLeaseRecoveryScheduler {
    private final PersistentJobCoordinator coordinator;
    private final RuntimeConfigService configs;
    private volatile long lastRecoveryAt;

    @Autowired
    public ExpiredLeaseRecoveryScheduler(PersistentJobCoordinator coordinator, RuntimeConfigService configs) {
        this.coordinator = coordinator;
        this.configs = configs;
    }

    /** 兼容不启动Spring容器的协调器单元测试。 */
    ExpiredLeaseRecoveryScheduler(PersistentJobCoordinator coordinator) {
        this(coordinator, null);
    }

    /** 每秒观察配置，按数据库中的有效回收间隔执行。 */
    @Scheduled(fixedDelay = 1000)
    public synchronized void recover() {
        long now = System.currentTimeMillis();
        long interval = configs == null ? 60_000L
                : configs.getLong("job.recovery-interval-ms", 60_000L, 1000L);
        if (lastRecoveryAt > 0 && now - lastRecoveryAt < interval) return;
        lastRecoveryAt = now;
        try {
            int maxRetries = configs == null ? 3 : configs.getInt("job.max-retry-count", 3, 0);
            int count = coordinator.recoverExpired(maxRetries);
            if (count > 0) log.info("已回收过期备份任务租约: count={}", count);
        } catch (RuntimeException error) {
            log.error("回收过期备份任务租约失败", error);
        }
    }
}
