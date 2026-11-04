package com.yigongbao.backup.job;

import com.yigongbao.backup.config.RuntimeConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 周期领取并执行到期的持久化任务；有效派发间隔来自数据库。 */
@Component
@RequiredArgsConstructor
public class JobDispatchScheduler {
    private final JobDispatcher dispatcher;
    private final RuntimeConfigService configs;
    private volatile long lastDispatchAt;

    /** 200毫秒基础心跳只负责观察数据库配置，实际派发频率由配置限制。 */
    @Scheduled(fixedDelay = 200)
    public synchronized void dispatch() {
        long now = System.currentTimeMillis();
        long interval = configs.getLong("job.dispatch-interval-ms", 1000L, 200L);
        if (lastDispatchAt > 0 && now - lastDispatchAt < interval) return;
        lastDispatchAt = now;
        while (dispatcher.dispatchOne()) {
            // 持续清空当前可领取任务。
        }
    }
}
