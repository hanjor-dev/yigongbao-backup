package com.yigongbao.backup.account;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yigongbao.backup.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** bk_sync_cursor 持久化实体，同时承载账号同步运行状态和最近结果。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("bk_sync_cursor")
public class BkSyncCursorEntity extends BaseEntity {
    private String cursorType;
    private String cursorValue;
    private String highWatermark;
    private Integer lookbackSeconds;
    private LocalDateTime lastSuccessAt;
    private LocalDateTime lastAttemptAt;
    private String syncStatus;
    private Integer lastItemCount;
    private String lastErrorMessage;
}
