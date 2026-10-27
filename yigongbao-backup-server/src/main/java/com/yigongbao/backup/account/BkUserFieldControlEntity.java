package com.yigongbao.backup.account;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yigongbao.backup.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 账号字段托管权；密码和启用状态本地修改后均标记为 LOCAL。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("bk_user_field_control")
public class BkUserFieldControlEntity extends BaseEntity {
    private Long userId;
    private String fieldName;
    private String ownership;
    private LocalDateTime localChangedAt;
    private Long localChangedBy;
    private String lastRemoteValueHash;
    private LocalDateTime releasedAt;
}
