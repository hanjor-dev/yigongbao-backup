package com.yigongbao.backup.order;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 订单快照数据访问接口。 */
@Mapper
public interface OrderSnapshotMapper extends BaseMapper<OrderSnapshotEntity> {
    @Select("""
            <script>
            SELECT DISTINCT source_order_id FROM bk_order_snapshot
            WHERE is_deleted=0 AND snapshot_status IN ('AVAILABLE','SUCCESS','BACKED_UP')
              AND source_order_id IN
              <foreach collection='orderIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>
            </script>
            """)
    List<Long> findBackedUpSourceOrderIds(@Param("orderIds") List<Long> orderIds);
}
