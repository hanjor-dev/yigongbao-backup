package com.yigongbao.backup.order;
import com.baomidou.mybatisplus.annotation.*; import com.yigongbao.backup.common.BaseEntity; import lombok.Data; import lombok.EqualsAndHashCode; import java.time.LocalDateTime;
/** 订单业务快照实体，保存完整业务 JSON 和三位置聚合状态。 */
@Data @EqualsAndHashCode(callSuper=true) @TableName("bk_order_snapshot")
public class OrderSnapshotEntity extends BaseEntity {private Long sourceOrderId; private String orderCode; private LocalDateTime orderCreatedAt; private String sourceStatus; private Boolean classicCase; private String orderRawJson; private String displayJson; private String relatedBusinessJson; private String manifestRevision; private String manifestSha256; private String snapshotStatus; private LocalDateTime backupCompletedAt; private LocalDateTime latestVerifiedAt; private String cloudRollup; private String hotRollup; private String coldRollup;}
