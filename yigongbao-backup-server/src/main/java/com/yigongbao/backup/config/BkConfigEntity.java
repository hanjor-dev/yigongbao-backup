package com.yigongbao.backup.config;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yigongbao.backup.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 持久化版本配置；每个版本保留一行，便于审计和并发CAS。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("bk_config")
public class BkConfigEntity extends BaseEntity {
    private String configKey;
    private String scope;
    private String valueType;
    private String configValue;
    private String secretRef;
    private Integer configVersion;
    private Long changedBy;
    private LocalDateTime changedAt;
}
