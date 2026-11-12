package com.yigongbao.backup.config;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/** 版本化配置表访问接口。 */
@Mapper
public interface BkConfigMapper extends BaseMapper<BkConfigEntity> {
    @Select("SELECT lock_name FROM bk_config_lock WHERE lock_name='GLOBAL' FOR UPDATE")
    String lockGlobalMutation();
}
