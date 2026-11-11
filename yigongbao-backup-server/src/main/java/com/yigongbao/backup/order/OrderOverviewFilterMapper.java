package com.yigongbao.backup.order;
import org.apache.ibatis.annotations.*;import java.util.List;
@Mapper public interface OrderOverviewFilterMapper{
 @SelectProvider(type=OrderOverviewFilterSqlProvider.class,method="selectIds") List<Long> selectIds(@Param("backupMode")String backupMode,@Param("storageAccessible")Boolean storageAccessible,@Param("storageIssue")String storageIssue,@Param("offset")long offset,@Param("limit")long limit);
 @SelectProvider(type=OrderOverviewFilterSqlProvider.class,method="count") long count(@Param("backupMode")String backupMode,@Param("storageAccessible")Boolean storageAccessible,@Param("storageIssue")String storageIssue);
}
