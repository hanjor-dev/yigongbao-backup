package com.yigongbao.backup.restore;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yigongbao.backup.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 恢复上传证据；按会话和资产身份幂等保存。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("bk_restore_file")
public class RestoreFileEntity extends BaseEntity {
    private Long restoreSessionId;
    private String assetIdentity;
    private Long originalFileId;
    private String targetObjectKey;
    private String uploadStatus;
    private String etag;
    private String versionId;
    private Long size;
    private String sha256;
    private String newUrl;
}
