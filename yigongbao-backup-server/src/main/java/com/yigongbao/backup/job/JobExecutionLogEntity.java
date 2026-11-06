package com.yigongbao.backup.job;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yigongbao.backup.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 任务执行过程日志。
 *
 * <p>该表保存管理员可读的业务事件，不保存预签名 URL、认证头或共享密钥。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("bk_job_execution_log")
public class JobExecutionLogEntity extends BaseEntity {
    /** 所属长任务。 */
    private Long jobId;
    /** 可为空；为空时表示任务级事件。 */
    private Long sourceOrderId;
    /** INFO、WARN 或 ERROR。 */
    private String logLevel;
    /** 任务领取、订单开始、文件上传等可读阶段。 */
    private String stage;
    /** 供程序筛选的稳定事件编码。 */
    private String eventCode;
    /** 面向管理员的中文日志正文。 */
    private String message;
    /** 已脱敏的结构化上下文 JSON。 */
    private String detailJson;
    /** 实际发生时间，独立于数据库写入时间。 */
    private LocalDateTime occurredAt;
}
