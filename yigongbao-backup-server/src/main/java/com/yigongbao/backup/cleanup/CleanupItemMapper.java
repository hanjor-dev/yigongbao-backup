package com.yigongbao.backup.cleanup;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/** 清理对象明细数据访问接口；查询使用 MyBatis-Plus Wrapper。 */
@Mapper
public interface CleanupItemMapper extends BaseMapper<CleanupItemEntity> {
    @Select("SELECT * FROM cos_cleanup_item WHERE cleanup_task_id=#{taskId} AND platform=#{platform} "
            + "AND bucket=#{bucket} AND object_key=#{objectKey} AND version_identity=#{versionIdentity} "
            + "AND is_deleted=0 LIMIT 1")
    CleanupItemEntity findIdentity(Long taskId, String platform, String bucket, String objectKey, String versionIdentity);
}
