package com.yigongbao.backup.storage.archive;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yigongbao.backup.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 一次不可变结果的整盘封存尝试。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("bk_volume_archive")
public class VolumeArchiveEntity extends BaseEntity {
    private Long jobId;
    private Long sourceVolumeId;
    private Long successorVolumeId;
    private Integer attemptNo;
    private String idempotencyKey;
    private String phase;
    private String archiveNote;
    private String displayNameSnapshot;
    private String offlineLabelNote;
    private String physicalLocation;
    private String remark;
    private Long orderCount;
    private Long orderPackageCount;
    private Long managedFileCount;
    private Long totalBytes;
    private LocalDateTime orderCreatedFrom;
    private LocalDateTime orderCreatedTo;
    private String periodLabel;
    private String contentManifestSha256;
    private Long manifestVersion;
    private Long checkpointItemId;
    private LocalDateTime verificationStartedAt;
    private LocalDateTime verificationCompletedAt;
    private LocalDateTime archivedAt;
    private Long requestedBy;
    private String failureCode;
    private String failureMessage;
    private String interruptedReason;
}
