package com.yigongbao.backup.job;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
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
    private String orderCode;
    private String orderCreatedAt;
    private String sourceStatus;
    private String orderBusinessJson;
    private Integer expectedFileCount;
    private Long expectedBytes;
    /** 由订单压缩包表实时汇总，不落在任务订单表中。 */
    @TableField(exist = false)
    private Long packageBytes;
    private String simulationPath;
    private String status;
    private String failureCode;
    private String failureMessage;
}
