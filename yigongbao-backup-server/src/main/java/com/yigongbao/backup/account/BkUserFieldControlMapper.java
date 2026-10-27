package com.yigongbao.backup.account;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/** 账号字段托管权 Mapper。 */
@Mapper
public interface BkUserFieldControlMapper extends BaseMapper<BkUserFieldControlEntity> {
    @Select("SELECT * FROM bk_user_field_control WHERE user_id=#{userId} AND field_name=#{fieldName} AND is_deleted=0 LIMIT 1")
    BkUserFieldControlEntity find(Long userId, String fieldName);
}
