package com.yigongbao.backup.account;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yigongbao.backup.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 登录失败限流桶；只保存不可逆摘要，不保存不存在账号的明文。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("bk_login_throttle")
public class LoginThrottleEntity extends BaseEntity {
    private String bucketKey;
    private String scopeType;
    private Integer failureCount;
    private LocalDateTime windowStartedAt;
    private LocalDateTime blockedUntil;
}
