package com.yigongbao.backup.restore;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/** 恢复会话数据访问接口；禁止 XML SQL。 */
@Mapper
public interface RestoreSessionMapper extends BaseMapper<RestoreSessionEntity> {
    @Select("SELECT * FROM bk_restore_session WHERE remote_session_id=#{remoteSessionId} AND is_deleted=0 LIMIT 1")
    RestoreSessionEntity findByRemoteSessionId(String remoteSessionId);

    @Select("SELECT * FROM bk_restore_session WHERE local_idempotency_key=#{localIdempotencyKey} AND is_deleted=0 LIMIT 1")
    RestoreSessionEntity findByLocalIdempotencyKey(String localIdempotencyKey);
}
