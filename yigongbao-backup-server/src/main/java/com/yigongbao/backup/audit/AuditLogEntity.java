package com.yigongbao.backup.audit;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yigongbao.backup.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** bk_audit_log 哈希链审计实体；只允许追加，不提供更新和删除业务接口。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("bk_audit_log")
public class AuditLogEntity extends BaseEntity {
    private String chainId;
    private Long sequenceNo;
    private Long actorUserId;
    private String actorRoleCode;
    private String clientAddress;
    private String action;
    private String objectType;
    private String objectId;
    private Long jobId;
    private String inputDigest;
    private String resultCode;
    private String failureReason;
    private String previousEntryHash;
    private String entryHash;
    private LocalDateTime occurredAt;
}
