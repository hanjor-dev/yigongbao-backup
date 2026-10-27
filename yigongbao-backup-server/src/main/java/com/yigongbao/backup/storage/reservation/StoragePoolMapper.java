package com.yigongbao.backup.storage.reservation;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/** 容量域数据访问接口。 */
@Mapper
public interface StoragePoolMapper extends BaseMapper<StoragePoolEntity> {
    @Select("SELECT * FROM bk_storage_pool WHERE id=#{id} AND is_deleted=0 FOR UPDATE")
    StoragePoolEntity selectForUpdate(Long id);
}
