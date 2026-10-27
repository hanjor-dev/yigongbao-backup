package com.yigongbao.backup.cleanup;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yigongbao.backup.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** COS 清理对象明细，逐物理版本保存删除结果和重试证据。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cos_cleanup_item")
public class CleanupItemEntity extends BaseEntity {
    private Long cleanupTaskId;
    private String platform;
    private String bucket;
    private String objectKey;
    private String versionIdentity;
    private Long sourceFileId;
    private String sharedReferenceStatus;
    private String deletionStatus;
    private Long versionBytes;
    private String errorMessage;
    private Integer retryCount;
    private LocalDateTime nextRetryAt;
}
