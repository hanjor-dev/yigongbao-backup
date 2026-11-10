package com.yigongbao.backup.storage.reservation;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Param;
import java.time.LocalDateTime;

/** 空间预留数据访问接口。 */
@Mapper
public interface SpaceReservationMapper extends BaseMapper<SpaceReservationEntity> {
    @Select("SELECT COALESCE(SUM(reserved_bytes),0) FROM bk_space_reservation WHERE storage_pool_id=#{poolId} AND status='ACTIVE' AND lease_until>CURRENT_TIMESTAMP(3) AND is_deleted=0")
    Long sumActive(Long poolId);

    @Select("SELECT COUNT(*) FROM bk_space_reservation WHERE storage_pool_id=#{poolId} AND status='ACTIVE' AND lease_until>#{now} AND is_deleted=0")
    long countActiveAt(@Param("poolId") long poolId, @Param("now") LocalDateTime now);

    /** 仅为仍持有有效执行租约的任务延长空间预留，终态/失租任务不会续租。 */
    @Update("""
            UPDATE bk_space_reservation r
            JOIN bk_job j ON j.id=r.job_id
            SET r.lease_until=#{leaseUntil}, r.update_time=#{now}
            WHERE r.status='ACTIVE' AND r.is_deleted=0
              AND j.status IN ('RUNNING','RETRYING_FAILED_ORDERS')
              AND j.lease_until>#{now}
              AND r.lease_until<#{leaseUntil}
            """)
    int renewForRunningJobs(@Param("now") LocalDateTime now,
                            @Param("leaseUntil") LocalDateTime leaseUntil);
}
