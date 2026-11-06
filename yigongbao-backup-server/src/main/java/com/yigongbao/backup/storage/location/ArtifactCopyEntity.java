package com.yigongbao.backup.storage.location;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yigongbao.backup.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 制品物理副本位置台账。
 *
 * <p>同一订单包或补传文件可以在热备、冷备或导入卷保留多个副本；该台账是
 * 后续读取路由与副本选择的事实来源。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("bk_artifact_copy")
public class ArtifactCopyEntity extends BaseEntity {
    private String artifactType;
    private Long artifactId;
    private String locationType;
    private Long locationRefId;
    /** 保存该副本的逻辑存储卷。 */
    private Long storageVolumeId;
    /** 卷内相对路径，禁止保存或依赖浏览器输入的绝对路径。 */
    private String storageKey;
    private String state;
    private String lifecycle;
    private Long size;
    private String sha256;
    private LocalDateTime lastVerifiedAt;
    private String errorCode;
}
