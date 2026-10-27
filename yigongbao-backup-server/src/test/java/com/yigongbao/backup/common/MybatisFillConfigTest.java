package com.yigongbao.backup.common;

import com.yigongbao.backup.config.MybatisFillConfig;
import org.apache.ibatis.reflection.MetaObject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MybatisFillConfigTest {
    @Test
    void fillsAuditDeleteAndVersionDefaults() {
        BaseEntity values = new BaseEntity() { };
        MetaObject meta = MetaObject.forObject(values,
                new org.apache.ibatis.reflection.factory.DefaultObjectFactory(),
                new org.apache.ibatis.reflection.wrapper.DefaultObjectWrapperFactory(),
                new org.apache.ibatis.reflection.DefaultReflectorFactory());
        new MybatisFillConfig().insertFill(meta);
        assertThat(values.getCreateTime()).isNotNull();
        assertThat(values.getUpdateTime()).isNotNull();
        assertThat(values.getIsDeleted()).isEqualTo(0);
        assertThat(values.getVersion()).isEqualTo(0);
    }
}
