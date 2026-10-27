package com.yigongbao.backup.cold;
import com.baomidou.mybatisplus.annotation.*; import com.yigongbao.backup.common.BaseEntity; import lombok.Data; import lombok.EqualsAndHashCode;
/** 冷备批次内单个订单包或资产的校验记录。 */
@Data @EqualsAndHashCode(callSuper=true) @TableName("bk_cold_batch_artifact") public class ColdBatchArtifactEntity extends BaseEntity {private Long batchId; private String artifactType; private Long artifactId; private String relativePath; private Long size; private String sha256; private String writeStatus; private String failureMessage;}
