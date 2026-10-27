package com.yigongbao.backup.storage.reservation;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yigongbao.backup.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 容量域持久化实体，对应本地热备/NAS/冷备存储池。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("bk_storage_pool")
public class StoragePoolEntity extends BaseEntity {
    private String capacityIdentity;
    private String storageType;
    private String rootPath;
    private String volumeIdentity;
    private String quotaIdentity;
    private String probeConfigJson;
    private String status;
    private Long capacityBytes;
    private Long availableBytes;
}
