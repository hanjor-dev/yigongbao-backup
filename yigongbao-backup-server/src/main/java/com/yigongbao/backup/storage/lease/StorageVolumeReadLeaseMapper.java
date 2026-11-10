package com.yigongbao.backup.storage.lease;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import java.time.LocalDateTime;

@Mapper
public interface StorageVolumeReadLeaseMapper extends BaseMapper<StorageVolumeReadLeaseEntity> {
    @Update("""
            UPDATE bk_storage_volume_read_lease SET status='EXPIRED',released_at=#{now},
              update_time=CURRENT_TIMESTAMP(3),version=version+1
            WHERE id=#{id} AND status='ACTIVE' AND fencing_token=#{token}
              AND expires_at<=#{now} AND version=#{version} AND is_deleted=0
            """)
    int expireIfStale(@Param("id")long id,@Param("token")long token,@Param("version")int version,@Param("now")LocalDateTime now);
}
