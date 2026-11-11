package com.yigongbao.backup.job;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.yigongbao.backup.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 任务内文件级处理证据。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("bk_job_file")
public class JobFileEntity extends BaseEntity {
    private Long jobId;
    private Long sourceOrderId;
    /** 文件所属或读取来源的卷；不以当前 ACTIVE 卷推断历史任务位置。 */
    private Long storageVolumeId;
    /** 卷内相对路径，不保存服务器物理绝对路径。 */
    private String storageKey;
    private String storageVolumeName;
    private String storageTier;
    /** 当前卷状态用于展示；任务发生时名称和层级仍保留在上面的审计快照字段中。 */
    @TableField(exist = false) private String currentStorageVolumeName;
    @TableField(exist = false) private String currentStorageTier;
    @TableField(exist = false) private String currentMountStatus;
    @TableField(exist = false) private String currentOperationStatus;
    @TableField(exist = false) private Boolean currentStorageResolved;
    @TableField(exist = false) private String currentStorageUnavailableReason;
    /** 任务文件列表展示字段，由同任务订单证据投影，不重复持久化。 */
    @TableField(exist = false) private String orderCode;
    /** 从包内相对路径或源对象键解析出的原始文件名。 */
    @TableField(exist = false) private String originalFilename;
    private String assetIdentity;
    private String step;
    private String status;
    private Long bytesDone;
    private Long bytesTotal;
    private String platform;
    private String bucket;
    private String objectKey;
    private String versionIdentity;
    private String tempPath;
    private String sha256;
    private String failureCode;
    private String failureMessage;
}
