package com.yigongbao.backup.config;

import com.yigongbao.backup.audit.MutationAuditInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebAuditConfiguration implements WebMvcConfigurer {
    private final MutationAuditInterceptor mutationAuditInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(mutationAuditInterceptor).addPathPatterns("/api/v1/**");
    }
}
