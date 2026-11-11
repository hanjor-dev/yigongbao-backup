package com.yigongbao.backup.job;
import com.baomidou.mybatisplus.annotation.*; import com.yigongbao.backup.common.BaseEntity; import lombok.Data; import lombok.EqualsAndHashCode; import java.time.LocalDateTime;
/** 持久化长任务实体，承载租约、进度、CAS 版本和失败证据。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("bk_job")
public class JobEntity extends BaseEntity {
    private String jobNo;
    private String jobType;
    private String status;
    private String currentStep;
    private String idempotencyKey;
    private Integer priority;
    private Long progressCurrent;
    private Long progressTotal;
    private String configSnapshotJson;
    /** 创建订单备份任务时锁定的目标卷；创建时无可用卷的失败任务允许为空，恢复任务不使用该字段。 */
    private Long targetStorageVolumeId;
    private String resultJson;
    private Long requestedBy;
    private LocalDateTime requestedAt;
    private String leaseOwner;
    private LocalDateTime leaseUntil;
    private LocalDateTime heartbeatAt;
    private Long fencingToken;
    private Integer retryCount;
    private LocalDateTime nextRetryAt;
    private Boolean cancelRequested;
    private String failureCode;
    private String failureMessage;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
