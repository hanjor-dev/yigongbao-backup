package com.yigongbao.backup.storage.volume;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yigongbao.backup.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 动态存储卷台账实体。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("bk_storage_volume")
public class StorageVolumeEntity extends BaseEntity {
    private String volumeCode;
    private String displayName;
    private String accessType;
    private String mountPath;
    private String rootPath;
    private String volumeFingerprint;
    private String markerJson;
    private Long capacityPoolId;
    private String tier;
    private String mountStatus;
    private String writeStatus;
    private String adminStatus;
    private String operationStatus;
    private Long operationRefId;
    private String archiveNote;
    private String offlineLabelNote;
    private String physicalLocation;
    private LocalDateTime archivedAt;
    private LocalDateTime orderCreatedFrom;
    private LocalDateTime orderCreatedTo;
    private Long archivedOrderCount;
    private Long archivedFileCount;
    private Long archivedBytes;
    private LocalDateTime lastVerifiedAt;
    private Integer priority;
    private LocalDateTime lastProbedAt;
    private String lastErrorCode;
    private String lastErrorMessage;
    private Long totalBytes;
    private Long usableBytes;
}
