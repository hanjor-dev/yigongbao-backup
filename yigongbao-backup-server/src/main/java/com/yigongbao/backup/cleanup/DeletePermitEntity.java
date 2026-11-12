package com.yigongbao.backup.cleanup;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yigongbao.backup.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** COS 删除 permit 持久化实体，支持重启后继续校验 attempt 和过期时间。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cos_delete_permit")
public class DeletePermitEntity extends BaseEntity {
    private String permitId;
    private Long cleanupTaskId;
    private Integer attemptNo;
    private String status;
    private String snapshotHash;
    private String objectSetHash;
    private String fencingToken;
    private String receiptId;
    private Integer receiptVersion;
    private String packageHash;
    private LocalDateTime issuedAt;
    private LocalDateTime expiresAt;
    private String nonce;
    private String claimOwner;
    private LocalDateTime claimUntil;
    private Long localFencingToken;
}
