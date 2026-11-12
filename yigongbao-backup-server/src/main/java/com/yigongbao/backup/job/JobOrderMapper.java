package com.yigongbao.backup.job;

/** 备份任务订单明细持久化接口，统一由 MyBatis-Plus 生成 SQL。 */

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface JobOrderMapper extends BaseMapper<JobOrderEntity> {
    @Update("UPDATE bk_job_order SET retry_count=retry_count+1 " +
            "WHERE job_id=#{jobId} AND source_order_id=#{sourceOrderId} AND is_deleted=0")
    int incrementRetryCount(@Param("jobId") long jobId, @Param("sourceOrderId") long sourceOrderId);

    @Update("UPDATE bk_job_order SET degrade_phase=#{phase} " +
            "WHERE job_id=#{jobId} AND source_order_id=#{sourceOrderId} AND is_deleted=0")
    int updateDegradePhase(@Param("jobId") long jobId, @Param("sourceOrderId") long sourceOrderId,
                           @Param("phase") String phase);

    @org.apache.ibatis.annotations.Select("SELECT COUNT(*) FROM bk_job_order o JOIN bk_job j ON j.id=o.job_id " +
            "WHERE o.source_storage_volume_id=#{volumeId} AND o.is_deleted=0 AND j.is_deleted=0 " +
            "AND j.status IN ('CREATED','RUNNING','RETRYING_FAILED_ORDERS')")
    long countRunningSourceReferences(@org.apache.ibatis.annotations.Param("volumeId") long volumeId);

    /** 直接在数据库排除已有可用快照，避免加载全部历史失败明细。 */
    @Select("""
            SELECT o.source_order_id FROM bk_job_order o
            WHERE o.status='FAILED' AND o.is_deleted=0 AND o.source_order_id IS NOT NULL
              AND NOT EXISTS (SELECT 1 FROM bk_order_snapshot s
                  WHERE s.source_order_id=o.source_order_id AND s.is_deleted=0
                    AND s.snapshot_status IN ('AVAILABLE','SUCCESS','BACKED_UP'))
            GROUP BY o.source_order_id ORDER BY MAX(o.id) ASC LIMIT #{limit}
            """)
    List<Long> findRetryableFailedSourceOrderIds(@Param("limit") int limit);
}
