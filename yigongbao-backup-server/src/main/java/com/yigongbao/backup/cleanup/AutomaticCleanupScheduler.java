package com.yigongbao.backup.cleanup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yigongbao.backup.config.RuntimeConfigService;
import com.yigongbao.backup.job.JobIdempotency;
import com.yigongbao.backup.job.PersistentJobCoordinator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 按数据库策略创建持久化COS自动清理任务。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AutomaticCleanupScheduler {
    private final RuntimeConfigService configs;
    private final PersistentJobCoordinator jobs;
    private final ObjectMapper mapper;
    private volatile long lastCreatedAt;

    /** 每分钟观察配置；只有线上通信与自动清理均启用时才创建任务。 */
    @Scheduled(fixedDelay = 60_000)
    public synchronized void schedule() {
        if (!configs.getBoolean("online.enabled", false)
                || !configs.getBoolean("cleanup.schedule.enabled", false)) return;
        long now = System.currentTimeMillis();
        long interval = configs.getLong("cleanup.schedule.interval-hours", 24L, 1L) * 3_600_000L;
        if (lastCreatedAt > 0 && now - lastCreatedAt < interval) return;
        int retentionDays = configs.getInt("cleanup.retention-days", 180, 1);
        int batchSize = configs.getInt("cleanup.batch-size", 100, 1);
        try {
            String payload = mapper.writeValueAsString(new AutomaticCleanupJobHandler.Payload(retentionDays, batchSize));
            long period = now / interval;
            jobs.create("AUTO_CLEANUP", JobIdempotency.key("AUTO_CLEANUP", 0L, period + ":" + payload),
                    0L, payload);
            lastCreatedAt = now;
            log.info("已创建COS自动清理任务: retentionDays={}, batchSize={}, period={}",
                    retentionDays, batchSize, period);
        } catch (Exception exception) {
            log.error("创建COS自动清理任务失败", exception);
        }
    }
}
