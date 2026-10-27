package com.yigongbao.backup.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** MyBatis-Plus 全局插件配置，确保公共版本字段的乐观锁更新参数正确注入。 */
@Configuration
public class MybatisPlusConfig {
    /** 注册乐观锁拦截器，配合 {@code BaseEntity.version} 生成安全的版本条件更新。 */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        return interceptor;
    }
}
