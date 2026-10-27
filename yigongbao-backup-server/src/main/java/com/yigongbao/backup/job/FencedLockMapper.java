package com.yigongbao.backup.job;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 数据库租约锁访问层。 */
@Mapper
public interface FencedLockMapper extends BaseMapper<FencedLockEntity> {
    /** 串行化同一锁键的获取、续租和释放。 */
    @Select("SELECT * FROM bk_lock WHERE lock_key=#{key} AND is_deleted=0 FOR UPDATE")
    FencedLockEntity selectForUpdate(@Param("key") String key);
}
