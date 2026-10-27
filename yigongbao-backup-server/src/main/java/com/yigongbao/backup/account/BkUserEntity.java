package com.yigongbao.backup.account;

import com.baomidou.mybatisplus.annotation.*;
import com.yigongbao.backup.common.BaseEntity;
import lombok.Data; import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

/** bk_user 持久化实体，保留线上托管字段和本地所有权字段。 */
@Data @EqualsAndHashCode(callSuper=true) @TableName("bk_user")
public class BkUserEntity extends BaseEntity {
    private Long sourceUserId; private String username; private String passwordHash; private String realName; private String phone; private String email;
    private String roleCode; private String enabledStatus; private Integer loginFailCount; private LocalDateTime lockUntil;
    private Integer rowVersion; private Boolean emergencyAdmin; private LocalDateTime sourceUpdatedAt; private LocalDateTime lastSyncedAt;
}
