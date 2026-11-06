package com.yigongbao.backup.order;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.yigongbao.backup.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 订单业务快照实体；标量字段结构化保存，嵌套对象和完整证据保存为 JSON。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("bk_order_snapshot")
public class OrderSnapshotEntity extends BaseEntity {
    /** REAL 为真实文件备份，METADATA_SIMULATION 为零字节占位模拟。 */
    private String backupMode;
    private Long sourceOrderId;
    private String orderCode;
    private String publicOrderCode;
    private LocalDateTime orderCreatedAt;
    private Integer orderType;
    private String orderTypeName;
    private Integer needsPhysicalDelivery;
    private String needsPhysicalDeliveryName;
    private String businessType;
    private String businessTypeName;
    private Long orgId;
    private String orgName;
    private Long operatorId;
    private String operatorName;
    private String operatorPhone;
    private Long operatorDeptId;
    private String operatorDeptName;
    private Long hospitalId;
    private String hospitalName;
    private Long areaId;
    private String areaName;
    private String fullAreaName;
    private Long hospitalDeptId;
    private String hospitalDeptName;
    private Long doctorId;
    private String doctorName;
    private String doctorPhone;
    private String patientName;
    private Integer patientAge;
    private String patientGender;
    private String patientGenderName;
    private Boolean classicCase;
    private Integer isUrgent;
    private Integer isPostal;
    private String postalAddress;
    private Long designerId;
    private String designerName;
    private LocalDateTime expectedDeliveryDate;
    private BigDecimal estimatedCost;
    private String dataEvaluationOpinion;
    private String designerRemark;
    private Integer phase;
    private String phaseName;
    private Integer status;
    private String statusName;
    private String sourceStatus;
    private String statusColorJson;
    private String regionalAuditJson;
    private String designAuditJson;
    private LocalDateTime designStartTime;
    private LocalDateTime designSubmitTime;
    private LocalDateTime productionStartTime;
    private LocalDateTime productionEndTime;
    private Integer sourceVersion;
    private String rebuildProjectListJson;
    /** 从重建项目 JSON 提取的项目名称，用于订单文件页统一关键词检索。 */
    private String rebuildProjectNames;
    private String orderRawJson;
    private String displayJson;
    private String relatedBusinessJson;
    private String manifestRevision;
    private String manifestSha256;
    private String snapshotStatus;
    private LocalDateTime backupCompletedAt;
    private LocalDateTime latestVerifiedAt;
    private String cloudRollup;
    private String hotRollup;
    private String coldRollup;
    /** 当前快照关联资产数量，列表查询时由 bk_asset 聚合回填。 */
    @TableField(exist = false)
    private Long assetCount;
    /** 当前快照源文件总大小，列表查询时由 bk_asset.source_size 聚合回填。 */
    @TableField(exist = false)
    private Long totalBytes;
}
