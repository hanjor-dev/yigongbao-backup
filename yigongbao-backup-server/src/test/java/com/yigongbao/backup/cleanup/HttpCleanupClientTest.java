package com.yigongbao.backup.cleanup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yigongbao.backup.remote.RecordingHttpClient;
import com.yigongbao.backup.remote.RemoteHttpSupport;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpCleanupClientTest {
    @Test
    void evaluatesPollsPermitAndDeletesOnlyWithPermit() {
        RecordingHttpClient transport = new RecordingHttpClient(request -> {
            String path = request.uri().getPath();
            String response = path.endsWith("/evaluate")
                    ? "{\"data\":{\"taskId\":\"task-1\",\"objectKeys\":[\"orders/o-1/a.zip\"],\"estimatedBytes\":12}}"
                    : path.endsWith("/permits") && request.method().equals("POST") && request.bodyPublisher().isPresent()
                    ? "{\"data\":{\"permitId\":\"permit-1\",\"taskId\":\"task-1\",\"objectKeys\":[\"orders/o-1/a.zip\"],\"status\":\"ACTIVE\"}}"
                    : "{\"code\":\"OK\",\"data\":{}}";
            return RecordingHttpClient.response(request, 200, response);
        });
        RemoteHttpSupport support = new RemoteHttpSupport(transport, new ObjectMapper(),
                URI.create("http://online.example"), Duration.ofSeconds(5), "NONE", "", "", "");
        HttpCleanupClient client = new HttpCleanupClient(support, new ObjectMapper());

        CleanupClient.Evaluation evaluation = client.evaluate(new CleanupClient.CleanupRequest(180, true));
        CleanupClient.Permit permit = client.pollPermit(evaluation.taskId());
        assertThatThrownBy(() -> client.delete(permit, "orders/o-1/a.zip"))
                .isInstanceOf(UnsupportedOperationException.class);

        assertThat(evaluation.objectKeys()).containsExactly("orders/o-1/a.zip");
        assertThat(evaluation.estimatedBytes()).isEqualTo(12L);
        assertThat(permit.status()).isEqualTo("ACTIVE");
        assertThat(transport.requests).extracting(request -> request.uri().getPath()).containsExactly(
                "/internal/cleanup/v1/evaluate",
                "/internal/cleanup/v1/tasks/task-1/permits");
    }
}
