package com.yigongbao.backup.backup;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/** 订单备份暂存状态访问接口。 */
@Mapper
public interface BackupStageMapper extends BaseMapper<BackupStageEntity> {
    @Select("""
            SELECT * FROM bk_backup_stage
            WHERE stage_status='SUPERSEDED' AND is_deleted=0
              AND cleanup_status IN ('PENDING','FAILED','RUNNING')
              AND (cleanup_next_at IS NULL OR cleanup_next_at<=CURRENT_TIMESTAMP(3))
            ORDER BY id LIMIT #{limit}
            """)
    List<BackupStageEntity> findCleanupCandidates(@Param("limit") int limit);

    @Update("""
            UPDATE bk_backup_stage
            SET cleanup_status='RUNNING', cleanup_next_at=DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 5 MINUTE),
                cleanup_error=NULL, update_time=CURRENT_TIMESTAMP(3), version=version+1
            WHERE id=#{id} AND stage_status='SUPERSEDED' AND is_deleted=0
              AND cleanup_status IN ('PENDING','FAILED','RUNNING')
              AND (cleanup_next_at IS NULL OR cleanup_next_at<=CURRENT_TIMESTAMP(3))
            """)
    int claimCleanup(@Param("id") long id);

    @Update("""
            UPDATE bk_backup_stage
            SET cleanup_status='SUCCEEDED', cleanup_next_at=NULL, cleanup_error=NULL,
                cleaned_at=CURRENT_TIMESTAMP(3), update_time=CURRENT_TIMESTAMP(3), version=version+1
            WHERE id=#{id} AND cleanup_status='RUNNING' AND is_deleted=0
            """)
    int completeCleanup(@Param("id") long id);

    @Update("""
            UPDATE bk_backup_stage
            SET cleanup_status='FAILED', cleanup_retry_count=cleanup_retry_count+1,
                cleanup_next_at=#{nextAttempt}, cleanup_error=#{error},
                update_time=CURRENT_TIMESTAMP(3), version=version+1
            WHERE id=#{id} AND cleanup_status='RUNNING' AND is_deleted=0
            """)
    int failCleanup(@Param("id") long id, @Param("error") String error,
                    @Param("nextAttempt") LocalDateTime nextAttempt);
}
