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
