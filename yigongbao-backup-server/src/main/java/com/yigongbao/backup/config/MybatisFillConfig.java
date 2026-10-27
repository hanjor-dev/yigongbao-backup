package com.yigongbao.backup.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** 自动填充公共审计字段；用户身份由后续 Sa-Token 认证上下文提供。 */
@Component
public class MybatisFillConfig implements MetaObjectHandler {
    @Override public void insertFill(MetaObject metaObject) {
        if (getFieldValByName("createTime", metaObject) == null) {
            setFieldValByName("createTime", LocalDateTime.now(), metaObject);
        }
        if (getFieldValByName("updateTime", metaObject) == null) {
            setFieldValByName("updateTime", LocalDateTime.now(), metaObject);
        }
        if (getFieldValByName("isDeleted", metaObject) == null) {
            setFieldValByName("isDeleted", 0, metaObject);
        }
        if (getFieldValByName("version", metaObject) == null) {
            setFieldValByName("version", 0, metaObject);
        }
    }
    @Override public void updateFill(MetaObject metaObject) {
        strictUpdateFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
    }
}
