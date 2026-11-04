package com.yigongbao.backup.order;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yigongbao.backup.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 文件资产与线上业务记录之间的版本化关联。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("bk_asset_relation")
public class AssetRelationEntity extends BaseEntity {
    private Long assetId;
    private String relationKind;
    private Long sourceRecordId;
    private String relationVersion;
    private String relationHash;
    private Long originalFileId;
    private String originalUrl;
    private String relationPayloadJson;
}
