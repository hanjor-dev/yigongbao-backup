package com.yigongbao.backup.storage.volume;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/** 存储卷台账数据访问接口。 */
@Mapper
public interface StorageVolumeMapper extends BaseMapper<StorageVolumeEntity> {
    @Select("SELECT * FROM bk_storage_volume WHERE tier = 'HOT' AND mount_status = 'ONLINE' " +
            "AND write_status = 'ACTIVE' AND is_deleted = 0 LIMIT 1 FOR UPDATE")
    StorageVolumeEntity findActiveForUpdate();

    @Select("SELECT * FROM bk_storage_volume WHERE id = #{id} AND is_deleted = 0 FOR UPDATE")
    StorageVolumeEntity findByIdForUpdate(long id);
}
