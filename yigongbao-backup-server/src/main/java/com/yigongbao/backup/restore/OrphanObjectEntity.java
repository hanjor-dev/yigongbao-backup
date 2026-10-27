package com.yigongbao.backup.restore;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yigongbao.backup.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 恢复中断后可能残留的 COS 对象；未确认前禁止自动清理。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("bk_orphan_object")
public class OrphanObjectEntity extends BaseEntity {
    private Long restoreSessionId;
    private String platform;
    private String bucket;
    private String objectKey;
    private String versionIdentity;
    private String confirmationStatus;
    private LocalDateTime earliestCleanupAt;
    private String cleanupStatus;
    private String cleanupResult;
}
