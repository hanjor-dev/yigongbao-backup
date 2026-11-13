package com.yigongbao.backup.backup;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 查询仍受数据库台账保护的订单包路径，供幽灵文件清理使用。 */
@Mapper
public interface UnregisteredBackupArtifactMapper {
    @Select("""
            SELECT storage_key FROM bk_artifact_copy
             WHERE storage_volume_id=#{volumeId} AND is_deleted=0
            UNION
            SELECT storage_key FROM bk_backup_stage
             WHERE storage_volume_id=#{volumeId} AND is_deleted=0
            UNION
            SELECT storage_key FROM bk_order_package
             WHERE storage_volume_id=#{volumeId} AND is_deleted=0
            """)
    List<String> findProtectedStorageKeys(@Param("volumeId") long volumeId);
}
