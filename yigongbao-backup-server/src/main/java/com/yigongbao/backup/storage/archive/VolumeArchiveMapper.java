package com.yigongbao.backup.storage.archive;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import java.time.LocalDateTime;

@Mapper
public interface VolumeArchiveMapper extends BaseMapper<VolumeArchiveEntity> {
    @Update("""
            UPDATE bk_volume_archive a JOIN bk_job j ON j.id=a.job_id AND j.is_deleted=0
            SET a.phase=#{phase}, a.verification_started_at=COALESCE(a.verification_started_at,#{startedAt}),
                a.update_time=CURRENT_TIMESTAMP(3), a.version=a.version+1
            WHERE a.id=#{archiveId} AND a.is_deleted=0 AND j.status IN ('RUNNING','RETRYING_FAILED_ORDERS')
              AND j.lease_owner=#{owner} AND j.fencing_token=#{token} AND j.lease_until>CURRENT_TIMESTAMP(3)
            """)
    int updatePhaseOwned(@Param("archiveId") long archiveId,@Param("phase") String phase,
                         @Param("startedAt") LocalDateTime startedAt,@Param("owner") String owner,@Param("token") long token);

    @Update("""
            UPDATE bk_volume_archive a JOIN bk_job j ON j.id=a.job_id AND j.is_deleted=0
            SET a.checkpoint_item_id=#{itemId}, a.update_time=CURRENT_TIMESTAMP(3), a.version=a.version+1
            WHERE a.id=#{archiveId} AND a.is_deleted=0 AND j.status IN ('RUNNING','RETRYING_FAILED_ORDERS')
              AND j.lease_owner=#{owner} AND j.fencing_token=#{token} AND j.lease_until>CURRENT_TIMESTAMP(3)
            """)
    int updateCheckpointOwned(@Param("archiveId") long archiveId,@Param("itemId") long itemId,
                              @Param("owner") String owner,@Param("token") long token);
}
