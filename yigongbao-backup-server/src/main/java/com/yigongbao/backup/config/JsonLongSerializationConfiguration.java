package com.yigongbao.backup.config;

import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 将 Java Long 统一序列化为 JSON 字符串，防止浏览器对雪花 ID 产生精度丢失。 */
@Configuration
public class JsonLongSerializationConfiguration {
    /** 同时覆盖包装类和基本类 long，保证所有 API 边界行为一致。 */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer longAsString() {
        return builder -> builder.serializerByType(Long.class, ToStringSerializer.instance)
                .serializerByType(Long.TYPE, ToStringSerializer.instance);
    }
}
