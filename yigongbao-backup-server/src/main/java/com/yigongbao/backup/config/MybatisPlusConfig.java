package com.yigongbao.backup.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.annotation.DbType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** MyBatis-Plus 全局插件配置，统一提供分页和公共版本字段乐观锁能力。 */
@Configuration
public class MybatisPlusConfig {
    /**
     * 注册分页与乐观锁拦截器。
     * 分页拦截器必须存在，否则 {@code selectPage} 只会查询全量记录并在内存中返回，
     * 导致订单、任务和账号列表的 current/size 参数失效。
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        return interceptor;
    }
}
