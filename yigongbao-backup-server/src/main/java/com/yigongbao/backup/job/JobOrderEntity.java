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
    /** 恢复任务创建时固化的订单包，避免重试时改读当前默认卷。 */
    private Long sourcePackageId;
    /** 恢复任务创建时固化的源存储卷。 */
    private Long sourceStorageVolumeId;
    /** 恢复任务创建时固化的卷内相对路径。 */
    private String sourceStorageKey;
    /** 恢复任务创建时固化的订单包完整性校验值。 */
    private String sourcePackageSha256;
    /** 该任务订单最终产物所在卷；备份为目标卷，恢复为已固化源卷。 */
    private Long storageVolumeId;
    /** 卷内相对键，任务历史不依赖当前 ACTIVE 卷反推。 */
    private String storageKey;
    /** 任务发生时卷名称快照。 */
    private String storageVolumeName;
    /** 任务发生时卷层级快照。 */
    private String storageTier;
    private String orderCode;
    private String orderCreatedAt;
    private String sourceStatus;
    private String orderBusinessJson;
    private Integer expectedFileCount;
    private Long expectedBytes;
    /** 由订单压缩包表实时汇总，不落在任务订单表中。 */
    @TableField(exist = false)
    private Long packageBytes;
    /** 当前卷状态与任务发生时快照分开展示，历史证据绝不被覆盖。 */
    @TableField(exist = false)
    private String currentStorageVolumeName;
    @TableField(exist = false)
    private String currentStorageTier;
    @TableField(exist = false)
    private String currentMountStatus;
    @TableField(exist = false)
    private String currentOperationStatus;
    private String simulationPath;
    private String status;
    private String failureCode;
    private String failureMessage;
}
