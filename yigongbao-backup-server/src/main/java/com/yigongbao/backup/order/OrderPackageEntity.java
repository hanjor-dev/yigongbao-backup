package com.yigongbao.backup.order;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yigongbao.backup.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 可下载和可校验的订单级压缩包。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("bk_order_package")
public class OrderPackageEntity extends BaseEntity {
    private String backupMode;
    private Long snapshotId;
    private String storageKey;
    private String packageFormat;
    private Integer fileCount;
    private Long sourceTotalSize;
    private Long packageSize;
    private String packageSha256;
    private String manifestSha256;
    private String status;
    private LocalDateTime verifiedAt;
    private LocalDateTime coldCoveredAt;
    private LocalDateTime hotDeletedAt;
    private Integer version;
}
