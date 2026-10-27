package com.yigongbao.backup.audit;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/** 审计链头 Mapper；FOR UPDATE 保证同一链的序号与前哈希不会并发重复。 */
@Mapper
public interface AuditChainHeadMapper extends BaseMapper<AuditChainHeadEntity> {
    @Select("SELECT * FROM bk_audit_chain_head WHERE chain_id=#{chainId} AND is_deleted=0 LIMIT 1 FOR UPDATE")
    AuditChainHeadEntity findForUpdate(String chainId);
}
