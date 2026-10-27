package com.yigongbao.backup.restore;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 恢复孤儿对象证据访问接口。 */
@Mapper
public interface OrphanObjectMapper extends BaseMapper<OrphanObjectEntity> {
}
