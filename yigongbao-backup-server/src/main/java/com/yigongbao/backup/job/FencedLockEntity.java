package com.yigongbao.backup.job;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yigongbao.backup.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 数据库租约锁记录；fencing token 只允许单调递增。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("bk_lock")
public class FencedLockEntity extends BaseEntity {
    private String lockKey;
    private Long holderJobId;
    private String leaseOwner;
    private LocalDateTime leaseUntil;
    private Long fencingToken;
}
