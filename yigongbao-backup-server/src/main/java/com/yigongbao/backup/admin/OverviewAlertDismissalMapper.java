package com.yigongbao.backup.admin;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface OverviewAlertDismissalMapper {
    @Select("SELECT alert_key AS alertKey FROM bk_overview_alert_dismissal ORDER BY id")
    List<DismissalRow> selectAll();

    @Insert("""
            INSERT INTO bk_overview_alert_dismissal
              (alert_key,alert_code,severity,title,dismissed_by,dismissed_at,create_time,update_time)
            VALUES
              (#{alertKey},#{alertCode},#{severity},#{title},#{dismissedBy},#{dismissedAt},CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3))
            ON DUPLICATE KEY UPDATE alert_key=alert_key
            """)
    int insertIgnore(@Param("alertKey") String alertKey,
                     @Param("alertCode") String alertCode,
                     @Param("severity") String severity,
                     @Param("title") String title,
                     @Param("dismissedBy") Long dismissedBy,
                     @Param("dismissedAt") LocalDateTime dismissedAt);

    @Delete("DELETE FROM bk_overview_alert_dismissal WHERE alert_key=#{alertKey}")
    int deleteByAlertKey(@Param("alertKey") String alertKey);

    record DismissalRow(String alertKey) { }
}
