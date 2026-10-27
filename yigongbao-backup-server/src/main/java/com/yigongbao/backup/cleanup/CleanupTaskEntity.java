package com.yigongbao.backup.cleanup;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yigongbao.backup.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** COS 清理任务持久化实体，状态是跨进程恢复的唯一事实来源。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cos_cleanup_task")
public class CleanupTaskEntity extends BaseEntity {
    private String taskNo;
    private String idempotencyKey;
    private Integer policyVersion;
    private String mode;
    private String status;
    private String snapshotHash;
    private String objectSetHash;
    private Long estimatedBytes;
    private Long actualBytes;
    private Long fencingToken;
    private String failureMessage;
}
