package com.yigongbao.backup.job;

/** 备份任务订单明细持久化接口，统一由 MyBatis-Plus 生成 SQL。 */

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface JobOrderMapper extends BaseMapper<JobOrderEntity> {
}
