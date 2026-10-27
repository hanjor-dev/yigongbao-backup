package com.yigongbao.backup.cleanup;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/** 删除授权数据访问接口；状态迁移由服务层条件更新控制。 */
@Mapper
public interface DeletePermitMapper extends BaseMapper<DeletePermitEntity> {
    @Select("SELECT * FROM cos_delete_permit WHERE permit_id=#{permitId} AND is_deleted=0 LIMIT 1")
    DeletePermitEntity findByPermitId(String permitId);
}
