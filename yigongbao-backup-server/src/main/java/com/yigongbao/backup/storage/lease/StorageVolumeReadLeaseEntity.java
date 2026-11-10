package com.yigongbao.backup.storage.lease;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yigongbao.backup.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 阻止读取过程中卷被离线或转换的持久化租约。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("bk_storage_volume_read_lease")
public class StorageVolumeReadLeaseEntity extends BaseEntity {
    private Long storageVolumeId;
    private String artifactType;
    private Long artifactId;
    private String purpose;
    private String owner;
    private Long fencingToken;
    private String status;
    private LocalDateTime heartbeatAt;
    private LocalDateTime expiresAt;
    private LocalDateTime releasedAt;
}
