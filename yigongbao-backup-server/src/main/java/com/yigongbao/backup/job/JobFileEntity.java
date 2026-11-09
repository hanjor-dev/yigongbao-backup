package com.yigongbao.backup.job;

import com.baomidou.mybatisplus.annotation.TableName;
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
