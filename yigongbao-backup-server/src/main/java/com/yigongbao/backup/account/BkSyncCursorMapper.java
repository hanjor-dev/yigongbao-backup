package com.yigongbao.backup.account;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 增量同步游标 Mapper。 */
@Mapper
public interface BkSyncCursorMapper extends BaseMapper<BkSyncCursorEntity> {

    @Select("SELECT * FROM bk_sync_cursor WHERE cursor_type=#{type} AND is_deleted=0 LIMIT 1")
    BkSyncCursorEntity find(@Param("type") String type);

    @Select("SELECT * FROM bk_sync_cursor WHERE cursor_type=#{type} AND is_deleted=0 LIMIT 1 FOR UPDATE")
    BkSyncCursorEntity findForUpdate(@Param("type") String type);
}
