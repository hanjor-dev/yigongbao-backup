package com.yigongbao.backup.storage.reservation;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;
import java.time.LocalDateTime;

/** 空间预留数据访问接口。 */
@Mapper
public interface SpaceReservationMapper extends BaseMapper<SpaceReservationEntity> {
    @Select("SELECT COALESCE(SUM(reserved_bytes),0) FROM bk_space_reservation WHERE storage_pool_id=#{poolId} AND status='ACTIVE' AND lease_until>CURRENT_TIMESTAMP(3) AND is_deleted=0")
    Long sumActive(Long poolId);

    @Select("SELECT COUNT(*) FROM bk_space_reservation WHERE storage_pool_id=#{poolId} AND status='ACTIVE' AND lease_until>#{now} AND is_deleted=0")
    long countActiveAt(@Param("poolId") long poolId, @Param("now") LocalDateTime now);
}
