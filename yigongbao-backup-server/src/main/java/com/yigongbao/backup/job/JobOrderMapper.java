package com.yigongbao.backup.job;

/** 备份任务订单明细持久化接口，统一由 MyBatis-Plus 生成 SQL。 */

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface JobOrderMapper extends BaseMapper<JobOrderEntity> {
    @org.apache.ibatis.annotations.Select("SELECT COUNT(*) FROM bk_job_order o JOIN bk_job j ON j.id=o.job_id " +
            "WHERE o.source_storage_volume_id=#{volumeId} AND o.is_deleted=0 AND j.is_deleted=0 " +
            "AND j.status IN ('CREATED','RUNNING','RETRYING_FAILED_ORDERS')")
    long countRunningSourceReferences(@org.apache.ibatis.annotations.Param("volumeId") long volumeId);
}
