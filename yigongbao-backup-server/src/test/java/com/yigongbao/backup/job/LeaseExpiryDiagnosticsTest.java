package com.yigongbao.backup.job;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LeaseExpiryDiagnosticsTest {
    @Test
    void describesLastKnownOrderFileStepAndLeaseTimes() {
        JobOrderMapper orders = mock(JobOrderMapper.class);
        JobFileMapper files = mock(JobFileMapper.class);
        JobOrderEntity order = new JobOrderEntity();
        order.setSourceOrderId(10086L);
        order.setStatus("RUNNING");
        JobFileEntity file = new JobFileEntity();
        file.setAssetIdentity("asset-9");
        file.setStep("BACKUP_DOWNLOAD");
        file.setBytesDone(1024L);
        file.setBytesTotal(4096L);
        file.setStatus("RUNNING");
        when(orders.selectOne(any(Wrapper.class))).thenReturn(order);
        when(files.selectOne(any(Wrapper.class))).thenReturn(file);
        JobEntity job = new JobEntity();
        job.setId(7L);
        job.setLeaseOwner("worker-a");
        job.setCurrentStep("BACKUP_ORDER_10086");
        job.setHeartbeatAt(LocalDateTime.of(2026, 11, 2, 17, 3, 50));
        job.setLeaseUntil(LocalDateTime.of(2026, 11, 2, 17, 4, 50));

        String message = new LeaseExpiryDiagnostics(orders, files).describe(job);

        assertThat(message).contains("订单=10086", "文件=asset-9", "文件步骤=BACKUP_DOWNLOAD",
                "进度=1024/4096", "执行器=worker-a", "最后心跳=2026-11-02 17:03:50",
                "租约截止=2026-11-02 17:04:50");
    }
}
