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
    @TableField(exist = false)
    private Boolean currentStorageResolved;
    @TableField(exist = false)
    private String currentStorageUnavailableReason;
    private String simulationPath;
    private String status;
    /** 该订单在当前任务内已消耗的重试次数；用于进程重启后继续执行同一重试预算。 */
    private Integer retryCount;
    /** 自动降级有界状态机阶段，用于进程重启续跑。 */
    private String degradePhase;
    private String failureCode;
    private String failureMessage;
    /** 最终成功但仍保留早期失败证据时，明确表示该订单由重试恢复。 */
    @TableField(exist = false)
    private Boolean retryRecovered;
    /** 仅供任务详情展示；服务端创建降级任务时仍会重新校验全部条件。 */
    @TableField(exist = false)
    private Boolean degradedBackupEligible;
    /** 由关联订单快照批量投影，任务订单表不重复保存。 */
    @TableField(exist = false)
    private String backupCompleteness;
    @TableField(exist = false)
    private Integer relationGapCount;
    /** 当前任务内由自动补偿形成的不完整成功；显式降级任务为 false。 */
    @TableField(exist = false)
    private Boolean autoDegraded;
}
