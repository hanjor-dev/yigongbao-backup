package com.yigongbao.backup.order;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yigongbao.backup.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 订单补传文件元数据；文件正文仍保存在热备存储目录。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("bk_supplement_file")
public class BkSupplementFileEntity extends BaseEntity {
    /** 补传文件实际所在存储卷。 */
    private Long storageVolumeId;
    private Long snapshotId;
    private String storageKey;
    private String originalFilename;
    private String internalFilename;
    private Long size;
    private String sha256;
    private String contentType;
    private String extension;
    private Long uploadedBy;
    private LocalDateTime uploadedAt;
    private String remark;
    private String hotStatus;
    private String coldCoverageStatus;
    private Long coveredBatchId;
}
