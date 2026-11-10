package com.yigongbao.backup.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yigongbao.backup.remote.RecordingHttpClient;
import com.yigongbao.backup.remote.RemoteHttpSupport;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 账号增量同步客户端契约测试。 */
class HttpRemoteAccountClientTest {
    @Test
    void mapsUserPageAndCarriesNextCursor() {
        RecordingHttpClient transport = new RecordingHttpClient(request -> RecordingHttpClient.response(request, 200,
                "{\"data\":{\"items\":[{\"id\":7,\"username\":\"u\",\"passwordHash\":\"hash\",\"status\":1,\"updateTime\":\"2026-01-01T00:00:00Z\"}],\"nextCursor\":{\"updateTime\":\"2026-01-01T00:00:00Z\",\"id\":7},\"hasMore\":true}}"));
        RemoteHttpSupport support = new RemoteHttpSupport(transport, new ObjectMapper(), URI.create("http://online.example"),
                Duration.ofSeconds(5), "shared-production-secret-32-bytes!");
        RemoteAccountClient.Page page = new HttpRemoteAccountClient(support).fetch(null, 200);

        assertThat(page.accounts()).singleElement().satisfies(account -> {
            assertThat(account.id()).isEqualTo(7L);
            assertThat(account.username()).isEqualTo("u");
            assertThat(account.status()).isEqualTo(1);
        });
        assertThat(page.nextCursor()).isEqualTo("2026-01-01T00:00:00Z|7");
        assertThat(transport.requests.getFirst().uri().getPath()).isEqualTo("/internal/backup/v1/accounts");
    }

    @Test
    void rejectsHasMoreResponseWithoutCompleteCursor() {
        RecordingHttpClient transport = new RecordingHttpClient(request -> RecordingHttpClient.response(request, 200,
                "{\"data\":{\"items\":[],\"hasMore\":true}}"));
        RemoteHttpSupport support = new RemoteHttpSupport(transport, new ObjectMapper(), URI.create("http://online.example"),
                Duration.ofSeconds(5), "shared-production-secret-32-bytes!");

        assertThatThrownBy(() -> new HttpRemoteAccountClient(support).fetch(null, 200))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("游标");
    }

    @Test
    void carriesFinalPageCursorSoNextRunDoesNotRestartFromEpoch() {
        RecordingHttpClient transport = new RecordingHttpClient(request -> RecordingHttpClient.response(request, 200,
                "{\"data\":{\"items\":[{\"id\":9,\"username\":\"last\",\"passwordHash\":\"$2a$10$stored-hash\",\"status\":1,\"updateTime\":\"2026-10-29T02:00:00Z\",\"deleted\":false}],"
                        + "\"nextCursor\":{\"updateTime\":\"2026-10-29T02:00:00Z\",\"id\":9},\"hasMore\":false}}"));
        RemoteHttpSupport support = new RemoteHttpSupport(transport, new ObjectMapper(), URI.create("http://online.example"),
                Duration.ofSeconds(5), "shared-production-secret-32-bytes!");

        RemoteAccountClient.Page page = new HttpRemoteAccountClient(support).fetch(null, 200);

        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isEqualTo("2026-10-29T02:00:00Z|9");
    }

    @Test
    void returnsAndCarriesStableHighWatermark() {
        RecordingHttpClient transport = new RecordingHttpClient(request -> RecordingHttpClient.response(request, 200,
                "{\"data\":{\"items\":[],\"hasMore\":false,\"highWatermark\":{\"updateTime\":\"2026-10-29T03:00:00Z\",\"id\":10}}}"));
        RemoteHttpSupport support = new RemoteHttpSupport(transport, new ObjectMapper(), URI.create("http://online.example"),
                Duration.ofSeconds(5), "shared-production-secret-32-bytes!");
        HttpRemoteAccountClient client = new HttpRemoteAccountClient(support);

        RemoteAccountClient.Page first = client.fetch(null, null, 200);
        client.fetch("2026-10-29T02:00:00Z|9", first.highWatermark(), 200);

        assertThat(first.highWatermark()).isEqualTo("2026-10-29T03:00:00Z|10");
        assertThat(transport.requests.get(1).uri().getQuery()).contains("highWatermarkTime=2026-10-29T03:00:00Z", "highWatermarkId=10");
    }
}
