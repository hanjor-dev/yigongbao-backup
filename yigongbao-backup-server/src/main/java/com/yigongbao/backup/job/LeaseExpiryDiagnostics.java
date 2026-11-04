package com.yigongbao.backup.job;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** 读取任务最后持久化的订单、文件和时间信息，形成可直接排障的租约过期原因。 */
@Component
@RequiredArgsConstructor
public class LeaseExpiryDiagnostics {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final JobOrderMapper orders;
    private final JobFileMapper files;

    /** 生成长度受控的任务租约诊断信息。 */
    public String describe(JobEntity job) {
        JobOrderEntity order = orders.selectOne(Wrappers.<JobOrderEntity>lambdaQuery()
                .eq(JobOrderEntity::getJobId, job.getId())
                .eq(JobOrderEntity::getStatus, "RUNNING")
                .orderByDesc(JobOrderEntity::getId).last("limit 1"));
        JobFileEntity file = files.selectOne(Wrappers.<JobFileEntity>lambdaQuery()
                .eq(JobFileEntity::getJobId, job.getId())
                .eq(JobFileEntity::getStatus, "RUNNING")
                .orderByDesc(JobFileEntity::getId).last("limit 1"));
        StringBuilder message = new StringBuilder("执行器租约过期，任务将由调度器恢复")
                .append("；执行器=").append(value(job.getLeaseOwner()))
                .append("；任务步骤=").append(value(job.getCurrentStep()));
        if (order != null) message.append("；订单=").append(order.getSourceOrderId());
        if (file != null) {
            message.append("；文件=").append(value(file.getAssetIdentity()))
                    .append("；文件步骤=").append(value(file.getStep()))
                    .append("；进度=").append(number(file.getBytesDone())).append('/').append(number(file.getBytesTotal()));
        }
        message.append("；最后心跳=").append(time(job.getHeartbeatAt()))
                .append("；租约截止=").append(time(job.getLeaseUntil()))
                .append("；可能原因=进程退出、数据库不可用、线程阻塞或心跳调度异常");
        return message.substring(0, Math.min(1000, message.length()));
    }

    private static String value(String value) { return value == null || value.isBlank() ? "未知" : value; }
    private static long number(Long value) { return value == null ? 0L : value; }
    private static String time(LocalDateTime value) { return value == null ? "未知" : TIME.format(value); }
}
