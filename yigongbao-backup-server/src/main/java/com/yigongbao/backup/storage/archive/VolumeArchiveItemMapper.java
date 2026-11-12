package com.yigongbao.backup.storage.archive;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface VolumeArchiveItemMapper extends BaseMapper<VolumeArchiveItemEntity> {
    @Select("""
            <script>
            SELECT archive_id AS archiveId, COUNT(*) AS verifiedCount
            FROM bk_volume_archive_item
            WHERE is_deleted=0 AND verification_status='VERIFIED' AND archive_id IN
            <foreach collection='archiveIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>
            GROUP BY archive_id
            </script>
            """)
    List<ArchiveVerifiedCount> countVerifiedByArchiveIds(@Param("archiveIds") List<Long> archiveIds);

    record ArchiveVerifiedCount(Long archiveId, Long verifiedCount) { }

    /** 一次聚合读取封存清单的校验完整性，避免修复状态时加载大量文件明细。 */
    @Select("""
            SELECT COUNT(*) AS totalCount,
              COALESCE(SUM(CASE WHEN verification_status='VERIFIED' THEN 1 ELSE 0 END),0) AS verifiedCount,
              COALESCE(SUM(CASE WHEN verification_status='FAILED' THEN 1 ELSE 0 END),0) AS failedCount,
              COALESCE(SUM(CASE WHEN verification_status IS NULL OR verification_status NOT IN ('VERIFIED','FAILED') THEN 1 ELSE 0 END),0) AS incompleteCount
            FROM bk_volume_archive_item
            WHERE archive_id=#{archiveId} AND is_deleted=0
            """)
    ArchiveVerificationSummary summarizeVerification(@Param("archiveId") long archiveId);

    record ArchiveVerificationSummary(Long totalCount, Long verifiedCount, Long failedCount, Long incompleteCount) { }

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
