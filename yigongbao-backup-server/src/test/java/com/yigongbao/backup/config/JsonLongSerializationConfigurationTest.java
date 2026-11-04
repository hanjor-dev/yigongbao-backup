package com.yigongbao.backup.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JsonLongSerializationConfigurationTest {
    @Test
    void serializesLongValuesAsStringsWithoutPrecisionLoss() throws Exception {
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        new JsonLongSerializationConfiguration().longAsString().customize(builder);

        String json = builder.build().writeValueAsString(Map.of("id", 2117177485547773987L));
        String primitiveJson = builder.build().writeValueAsString(new PrimitiveId(2117177485547773987L));

        assertThat(json).isEqualTo("{\"id\":\"2117177485547773987\"}");
        assertThat(primitiveJson).isEqualTo("{\"id\":\"2117177485547773987\"}");
    }

    private record PrimitiveId(long id) { }
}
