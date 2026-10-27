package com.yigongbao.backup.cold;
import com.baomidou.mybatisplus.annotation.*; import com.yigongbao.backup.common.BaseEntity; import lombok.Data; import lombok.EqualsAndHashCode; import java.time.LocalDateTime;
/** 冷备介质登记实体，磁盘必须由管理员明确选择。 */
@Data @EqualsAndHashCode(callSuper=true) @TableName("bk_cold_disk") public class ColdDiskEntity extends BaseEntity {private String diskCode; private String volumeSerial; private String markerUuid; private String fileSystem; private String label; private String physicalLocation; private String status; private Long capacityBytes; private Long freeBytes; private LocalDateTime lastSeenAt; private LocalDateTime lastVerifiedAt;}
