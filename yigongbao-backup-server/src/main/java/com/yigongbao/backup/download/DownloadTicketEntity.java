package com.yigongbao.backup.download;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yigongbao.backup.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 短时单次下载凭据；数据库只保存令牌摘要。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("bk_download_ticket")
public class DownloadTicketEntity extends BaseEntity {
    private String tokenHash;
    private Long actorUserId;
    private String actorRoleCode;
    private String artifactType;
    private Long artifactId;
    private String downloadName;
    private String status;
    private LocalDateTime expiresAt;
    private LocalDateTime consumedAt;
}
