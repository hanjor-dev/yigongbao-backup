package com.yigongbao.backup.account;
import com.baomidou.mybatisplus.core.mapper.BaseMapper; import org.apache.ibatis.annotations.Mapper; import org.apache.ibatis.annotations.Select; import org.apache.ibatis.annotations.Update;
/** 本地账号数据库访问接口；禁止 XML Mapper。 */
@Mapper public interface BkUserMapper extends BaseMapper<BkUserEntity> {
    @Select("SELECT * FROM bk_user WHERE source_user_id=#{sourceUserId} AND is_deleted=0 LIMIT 1") BkUserEntity findBySourceUserId(Long sourceUserId);
    @Select("SELECT * FROM bk_user WHERE username=#{username} AND is_deleted=0 LIMIT 1") BkUserEntity findByUsername(String username);
    @Select("SELECT * FROM bk_user WHERE emergency_admin=1 AND is_deleted=0 LIMIT 1") BkUserEntity findEmergencyAdmin();
    @Update("UPDATE bk_user SET login_fail_count=COALESCE(login_fail_count,0)+1, "
            + "lock_until=CASE WHEN COALESCE(login_fail_count,0)+1>=5 THEN DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 15 MINUTE) ELSE lock_until END, "
            + "version=version+1, update_time=CURRENT_TIMESTAMP(3) WHERE id=#{id} AND is_deleted=0")
    int recordLoginFailure(Long id);
    @Update("UPDATE bk_user SET login_fail_count=0, lock_until=NULL, version=version+1, update_time=CURRENT_TIMESTAMP(3) WHERE id=#{id} AND is_deleted=0")
    int clearLoginFailures(Long id);
}
