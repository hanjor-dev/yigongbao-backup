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
    void preservesStructuredRelationBrokenEvidenceFromHttp409() {
        @SuppressWarnings("unchecked") HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(409);
        when(response.body()).thenReturn("""
                {"code":"RELATION_BROKEN","message":"订单文件关系不完整","requestId":"req-409",
                 "retryable":false,"details":[{"reason":"订单文件不存在: 2091395344967614466"}]}
                """);
        RemoteHttpSupport support = new RemoteHttpSupport(mock(HttpClient.class), new ObjectMapper(),
                URI.create("http://localhost:8081/api"), Duration.ofSeconds(5), "a".repeat(32));

        assertThatThrownBy(() -> support.ensureSuccess(response))
                .isInstanceOf(RemoteCallException.class)
                .satisfies(error -> {
                    RemoteCallException remote = (RemoteCallException) error;
                    org.assertj.core.api.Assertions.assertThat(remote.code()).isEqualTo("RELATION_BROKEN");
                    org.assertj.core.api.Assertions.assertThat(remote.requestId()).isEqualTo("req-409");
                    org.assertj.core.api.Assertions.assertThat(remote.reason())
                            .isEqualTo("订单文件不存在: 2091395344967614466");
                    org.assertj.core.api.Assertions.assertThat(remote.retryable()).isFalse();
                });
    }

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
