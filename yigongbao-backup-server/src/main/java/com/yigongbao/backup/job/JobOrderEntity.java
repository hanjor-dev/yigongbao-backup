package com.yigongbao.backup.job;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yigongbao.backup.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 任务内订单级结果。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("bk_job_order")
public class JobOrderEntity extends BaseEntity {
    private Long jobId;
    private Long sourceOrderId;
    private Long snapshotId;
    private String status;
    private String failureCode;
    private String failureMessage;
}
