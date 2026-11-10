package com.yigongbao.backup.order;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yigongbao.backup.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 降级备份时未能解析成真实资产的业务关系证据；不代表文件或占位对象。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("bk_relation_gap")
public class RelationGapEntity extends BaseEntity {
    private Long snapshotId;
    private String relationKind;
    private String sourceRecordId;
    private String relationOwnerKey;
    private String relationValue;
    private String originalFileId;
    private String reasonCode;
    private String reason;
    private String relationHash;
}
