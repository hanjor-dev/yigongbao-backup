package com.yigongbao.backup.remote;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/** 动态代理必须保持惰性，线上能力关闭时不得在应用启动阶段创建网络资源。 */
class RemoteHttpSupportDynamicTest {

    @Test
    void dynamicWrapperDoesNotCreateUnusedHttpClientAtConstructionTime() throws Exception {
        RemoteHttpSupport support = new RemoteHttpSupport(new ObjectMapper(), () -> null);
        Field client = RemoteHttpSupport.class.getDeclaredField("client");
        client.setAccessible(true);

        assertThat(client.get(support)).isNull();
    }
}
