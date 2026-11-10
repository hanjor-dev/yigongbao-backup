package com.yigongbao.backup.backup;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 订单备份暂存状态访问接口。 */
@Mapper
public interface BackupStageMapper extends BaseMapper<BackupStageEntity> { }
