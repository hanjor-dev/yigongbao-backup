package com.yigongbao.backup.storage.archive;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yigongbao.backup.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 封存时冻结的单个受管物理文件。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("bk_volume_archive_item")
public class VolumeArchiveItemEntity extends BaseEntity {
    private Long archiveId;
    private Long storageVolumeId;
    private String storageKey;
    private String artifactType;
    private Long artifactId;
    private Long snapshotId;
    private Long sourceOrderId;
    /** 关联订单的业务编号，仅用于详情展示，不持久化到封存明细表。 */
    @TableField(exist = false)
    private String orderCode;
    private LocalDateTime orderCreatedAt;
    private Long expectedSize;
    private String expectedSha256;
    private String expectedManifestSha256;
    private String verificationStatus;
    private Long actualSize;
    private String actualSha256;
    private LocalDateTime verifiedAt;
    private String failureCode;
    private String failureMessage;
}
