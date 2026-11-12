package com.yigongbao.backup.audit;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/** 审计日志 Mapper；链头查询使用行锁，禁止 XML。 */
@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLogEntity> {
    @Select("""
            <script>
            SELECT * FROM bk_audit_log WHERE is_deleted=0
            <if test='occurredAt != null and id != null'>
              AND (occurred_at &lt; #{occurredAt} OR (occurred_at=#{occurredAt} AND id &lt; #{id}))
            </if>
            ORDER BY occurred_at DESC,id DESC LIMIT #{limit}
            </script>
            """)
    List<AuditLogEntity> findPage(@Param("occurredAt") LocalDateTime occurredAt,
                                  @Param("id") Long id, @Param("limit") int limit);
}
