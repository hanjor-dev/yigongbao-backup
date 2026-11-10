package com.yigongbao.backup.storage.archive;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface VolumeArchiveItemMapper extends BaseMapper<VolumeArchiveItemEntity> {
    /** 校验结果与任务租约在同一条 SQL 中核对，阻止失去 fencing token 的旧执行器落库。 */
    @Update("""
            UPDATE bk_volume_archive_item i
            JOIN bk_volume_archive a ON a.id=i.archive_id AND a.is_deleted=0
            JOIN bk_job j ON j.id=a.job_id AND j.is_deleted=0
            SET i.actual_size=#{actualSize}, i.actual_sha256=#{actualSha256},
                i.verified_at=#{verifiedAt}, i.verification_status=#{status},
                i.failure_code=#{failureCode}, i.failure_message=#{failureMessage},
                i.update_time=CURRENT_TIMESTAMP(3), i.version=i.version+1
            WHERE i.id=#{itemId} AND i.version=#{version} AND i.is_deleted=0
              AND j.status IN ('RUNNING','RETRYING_FAILED_ORDERS')
              AND j.lease_owner=#{owner} AND j.fencing_token=#{token}
              AND j.lease_until>CURRENT_TIMESTAMP(3)
            """)
    int updateVerificationOwned(@Param("itemId") long itemId, @Param("version") int version,
                                @Param("actualSize") Long actualSize, @Param("actualSha256") String actualSha256,
                                @Param("verifiedAt") LocalDateTime verifiedAt, @Param("status") String status,
                                @Param("failureCode") String failureCode, @Param("failureMessage") String failureMessage,
                                @Param("owner") String owner, @Param("token") long token);
}
