package com.yigongbao.backup.config;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 版本化配置表访问接口。 */
@Mapper
public interface BkConfigMapper extends BaseMapper<BkConfigEntity> {
}
