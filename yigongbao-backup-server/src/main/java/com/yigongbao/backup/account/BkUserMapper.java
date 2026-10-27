package com.yigongbao.backup.account;
import com.baomidou.mybatisplus.core.mapper.BaseMapper; import org.apache.ibatis.annotations.Mapper; import org.apache.ibatis.annotations.Select;
/** 本地账号数据库访问接口；禁止 XML Mapper。 */
@Mapper public interface BkUserMapper extends BaseMapper<BkUserEntity> {
    @Select("SELECT * FROM bk_user WHERE source_user_id=#{sourceUserId} AND is_deleted=0 LIMIT 1") BkUserEntity findBySourceUserId(Long sourceUserId);
    @Select("SELECT * FROM bk_user WHERE username=#{username} AND is_deleted=0 LIMIT 1") BkUserEntity findByUsername(String username);
}
