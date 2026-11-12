package com.yigongbao.backup.download;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface DownloadTicketMapper extends BaseMapper<DownloadTicketEntity> {
    @Select("SELECT * FROM bk_download_ticket WHERE token_hash=#{hash} AND is_deleted=0 LIMIT 1")
    DownloadTicketEntity findByTokenHash(@Param("hash") String hash);

    @Update("UPDATE bk_download_ticket SET status='CONSUMED',consumed_at=#{now},update_time=#{now},version=version+1 " +
            "WHERE id=#{id} AND status='ISSUED' AND expires_at>#{now} AND is_deleted=0")
    int consumeOnce(@Param("id") long id, @Param("now") LocalDateTime now);

    @Delete("DELETE FROM bk_download_ticket WHERE (expires_at<#{before} OR consumed_at<#{before})")
    int purgeBefore(@Param("before") LocalDateTime before);
}
