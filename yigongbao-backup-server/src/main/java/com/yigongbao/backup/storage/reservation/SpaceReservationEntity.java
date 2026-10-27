package com.yigongbao.backup.storage.reservation;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yigongbao.backup.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

/** 持久化空间预留记录，带租约和 fencing token。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("bk_space_reservation")
public class SpaceReservationEntity extends BaseEntity {
    private Long storagePoolId;
    private Long jobId;
    private Long reservedBytes;
    private LocalDateTime leaseUntil;
    private Long fencingToken;
    private String status;
}
