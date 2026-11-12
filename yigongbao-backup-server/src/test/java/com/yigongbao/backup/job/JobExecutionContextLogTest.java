package com.yigongbao.backup.job;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** 验证执行上下文可写入任务级及订单级可查询日志。 */
class JobExecutionContextLogTest {

    @Test
    void recordsTaskAndOrderScopedBusinessEvents() {
        PersistentJobCoordinator coordinator = mock(PersistentJobCoordinator.class);
        JobExecutionLogService logs = mock(JobExecutionLogService.class);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(3).run();
            return null;
        }).when(coordinator).withinActiveLease(any(Long.class), any(String.class), any(Long.class), any(Runnable.class));
        JobExecutionContext context = new JobExecutionContext(coordinator, null, logs, 99L, "worker-1", 2L, 60L);

        context.taskInfo("TASK_CLAIMED", "任务领取", "恢复任务已领取｜目标订单数=2", Map.of("orderCount", 2));
        context.orderInfo(31L, "ORDER_STARTED", "订单开始", "开始恢复订单｜订单号=20260707000021", Map.of("orderNo", "20260707000021"));

        verify(logs).info(99L, null, "TASK_CLAIMED", "任务领取", "恢复任务已领取｜目标订单数=2", Map.of("orderCount", 2));
        verify(logs).info(99L, 31L, "ORDER_STARTED", "订单开始", "开始恢复订单｜订单号=20260707000021", Map.of("orderNo", "20260707000021"));
    }
}
