package com.yigongbao.backup.backup;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yigongbao.backup.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 线上回执接受前的本地订单包暂存记录；暂存记录本身不进入订单快照查询。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("bk_backup_stage")
public class BackupStageEntity extends BaseEntity {
    private Long jobId;
    private Long sourceOrderId;
    private String idempotencyKey;
    private String manifestJson;
    private String resultJson;
    private String manifestSha256;
    private String packageSha256;
    private Long packageSize;
    private Long storageVolumeId;
    private String storageKey;
    private String backupCompleteness;
    private String stageStatus;
    private Long publishedSnapshotId;
    private java.time.Instant receiptCompletedAt;
    private LocalDateTime receiptAcceptedAt;
    private LocalDateTime publishedAt;
    private LocalDateTime supersededAt;
}
