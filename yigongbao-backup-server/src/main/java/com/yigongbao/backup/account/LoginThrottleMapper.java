package com.yigongbao.backup.account;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface LoginThrottleMapper extends BaseMapper<LoginThrottleEntity> {
    @Select("SELECT * FROM bk_login_throttle WHERE bucket_key=#{bucketKey} AND is_deleted=0 FOR UPDATE")
    LoginThrottleEntity selectForUpdate(@Param("bucketKey") String bucketKey);

    @Delete("DELETE FROM bk_login_throttle WHERE bucket_key=#{bucketKey}")
    int deleteByBucketKey(@Param("bucketKey") String bucketKey);

    @Delete("DELETE FROM bk_login_throttle WHERE update_time<DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 DAY) "
            + "AND (blocked_until IS NULL OR blocked_until<CURRENT_TIMESTAMP(3))")
    int deleteExpired();
}
