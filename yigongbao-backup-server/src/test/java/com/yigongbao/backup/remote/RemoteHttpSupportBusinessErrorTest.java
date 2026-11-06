package com.yigongbao.backup.remote;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** HTTP 成功但业务错误信封不能被当作正常 data 响应解析。 */
class RemoteHttpSupportBusinessErrorTest {
    @Test
    void rejectsBusinessErrorEnvelopeReturnedWithHttp200() {
        @SuppressWarnings("unchecked") HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"code\":\"INTERNAL_ERROR\",\"message\":\"恢复准备失败\",\"retryable\":false}");
        RemoteHttpSupport support = new RemoteHttpSupport(mock(HttpClient.class), new ObjectMapper(),
                URI.create("http://localhost:8081/api"), Duration.ofSeconds(5), "a".repeat(32));

        assertThatThrownBy(() -> support.data(response))
                .isInstanceOf(RemoteCallException.class)
                .hasMessageContaining("INTERNAL_ERROR")
                .hasMessageContaining("恢复准备失败");
    }
}
