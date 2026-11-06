package com.yigongbao.backup.job;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 任务执行日志持久化接口；使用 MyBatis-Plus，禁止 XML SQL。 */
@Mapper
public interface JobExecutionLogMapper extends BaseMapper<JobExecutionLogEntity> {
}
