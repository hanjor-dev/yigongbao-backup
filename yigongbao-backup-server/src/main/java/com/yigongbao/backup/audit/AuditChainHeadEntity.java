package com.yigongbao.backup.audit;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yigongbao.backup.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 审计链头；追加审计时必须在事务内锁定此行。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("bk_audit_chain_head")
public class AuditChainHeadEntity extends BaseEntity {
    private String chainId;
    private Long lastSequenceNo;
    private String lastEntryHash;
}
