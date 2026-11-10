package com.yigongbao.backup.restore;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yigongbao.backup.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 本地恢复会话；用于进程重启后追踪远程会话与提交状态。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("bk_restore_session")
public class RestoreSessionEntity extends BaseEntity {
    private Long localJobId;
    private String localIdempotencyKey;
    private String remoteSessionId;
    private Long sourceOrderId;
    private Long snapshotId;
    private String conflictPolicy;
    private String preparedRevision;
    private String signedPlanDigest;
    private String relationSetHash;
    private Integer manifestSchemaVersion;
    private String backupCompleteness;
    private String relationshipStateHash;
    private String status;
    private java.time.LocalDateTime protectionUntil;
}
