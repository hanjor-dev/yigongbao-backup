package com.yigongbao.backup.job;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** 管理端任务执行日志的受控分页读取服务。 */
@Service
@RequiredArgsConstructor
public class JobExecutionLogQueryService {
    private final JobExecutionLogMapper mapper;

    /**
     * 按发生顺序分页读取任务日志；传入订单 ID 时仅返回该订单的文件及订单过程事件。
     */
    public LogPage page(long jobId, Long sourceOrderId, long current, long size) {
        if (jobId < 1 || current < 1 || size < 1 || size > 100) {
            throw new IllegalArgumentException("日志分页参数非法");
        }
        Page<JobExecutionLogEntity> page = mapper.selectPage(Page.of(current, size),
                Wrappers.<JobExecutionLogEntity>lambdaQuery()
                        .eq(JobExecutionLogEntity::getJobId, jobId)
                        .eq(sourceOrderId != null, JobExecutionLogEntity::getSourceOrderId, sourceOrderId)
                        .orderByAsc(JobExecutionLogEntity::getOccurredAt)
                        .orderByAsc(JobExecutionLogEntity::getId));
        return new LogPage(page.getRecords(), page.getTotal(), page.getCurrent(), page.getSize(), page.getPages());
    }

    /** 仅读取任务自身事件，不把每个文件的订单日志混入默认任务控制台。 */
    public LogPage taskPage(long jobId, long current, long size) {
        if (jobId < 1 || current < 1 || size < 1 || size > 100) {
            throw new IllegalArgumentException("日志分页参数非法");
        }
        Page<JobExecutionLogEntity> page = mapper.selectPage(Page.of(current, size),
                Wrappers.<JobExecutionLogEntity>lambdaQuery()
                        .eq(JobExecutionLogEntity::getJobId, jobId)
                        .isNull(JobExecutionLogEntity::getSourceOrderId)
                        .orderByAsc(JobExecutionLogEntity::getOccurredAt)
                        .orderByAsc(JobExecutionLogEntity::getId));
        return new LogPage(page.getRecords(), page.getTotal(), page.getCurrent(), page.getSize(), page.getPages());
    }

    /** 管理页通用的日志分页响应。 */
    public record LogPage(List<JobExecutionLogEntity> records, long total, long current, long size, long pages) {
    }
}
