package com.yigongbao.backup.cleanup;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/** 清理任务数据访问接口；禁止 XML。 */
@Mapper
public interface CleanupTaskMapper extends BaseMapper<CleanupTaskEntity> {
    @Select("SELECT * FROM cos_cleanup_task WHERE idempotency_key=#{key} AND is_deleted=0 LIMIT 1")
    CleanupTaskEntity findByIdempotencyKey(String key);

    @Select("SELECT * FROM cos_cleanup_task WHERE task_no=#{taskNo} AND is_deleted=0 LIMIT 1")
    CleanupTaskEntity findByTaskNo(String taskNo);
}
