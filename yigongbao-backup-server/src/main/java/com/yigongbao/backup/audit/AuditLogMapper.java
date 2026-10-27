package com.yigongbao.backup.audit;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/** 审计日志 Mapper；链头查询使用行锁，禁止 XML。 */
@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLogEntity> {
}
