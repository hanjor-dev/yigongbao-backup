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

    /** 锁定活动卷台账，即使卷已被健康巡检降级为不可用也允许执行受控恢复。 */
    @Select("SELECT * FROM bk_storage_volume WHERE tier = 'HOT' " +
            "AND write_status = 'ACTIVE' AND is_deleted = 0 LIMIT 1 FOR UPDATE")
    StorageVolumeEntity findActiveLedgerForUpdate();

    @Select("SELECT * FROM bk_storage_volume WHERE id = #{id} AND is_deleted = 0 FOR UPDATE")
    StorageVolumeEntity findByIdForUpdate(long id);
}
