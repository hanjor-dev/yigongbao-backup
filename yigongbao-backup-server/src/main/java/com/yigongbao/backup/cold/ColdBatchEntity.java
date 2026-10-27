package com.yigongbao.backup.cold;
import com.baomidou.mybatisplus.annotation.*; import com.yigongbao.backup.common.BaseEntity; import lombok.Data; import lombok.EqualsAndHashCode; import java.time.LocalDateTime;
/** 冷备批次持久化实体，记录选盘、校验和热备清理状态。 */
@Data @EqualsAndHashCode(callSuper=true) @TableName("bk_cold_batch") public class ColdBatchEntity extends BaseEntity {private String batchNo; private LocalDateTime orderTimeFrom; private LocalDateTime orderTimeTo; private Long diskId; private String format; private String status; private Integer orderCount; private Integer artifactCount; private Long totalSize; private String batchManifestSha256; private LocalDateTime completedAt; private String hotCleanupStatus;}
