package com.yigongbao.backup.restore;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 恢复上传证据数据访问接口；查询使用 MyBatis-Plus Wrapper。 */
@Mapper
public interface RestoreFileMapper extends BaseMapper<RestoreFileEntity> {
}
