package com.yigongbao.backup.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yigongbao.backup.remote.RecordingHttpClient;
import com.yigongbao.backup.remote.RemoteHttpSupport;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** 账号增量同步客户端契约测试。 */
class HttpRemoteAccountClientTest {
    @Test
    void mapsUserPageAndCarriesNextCursor() {
        RecordingHttpClient transport = new RecordingHttpClient(request -> RecordingHttpClient.response(request, 200,
                "{\"data\":{\"items\":[{\"id\":7,\"username\":\"u\",\"passwordHash\":\"hash\",\"status\":1,\"updateTime\":\"2026-01-01T00:00:00Z\"}],\"nextCursor\":{\"updateTime\":\"2026-01-01T00:00:00Z\",\"id\":7},\"hasMore\":true}}"));
        RemoteHttpSupport support = new RemoteHttpSupport(transport, new ObjectMapper(), URI.create("http://online.example"),
                Duration.ofSeconds(5), "NONE", "", "", "");
        RemoteAccountClient.Page page = new HttpRemoteAccountClient(support).fetch(null, 200);

        assertThat(page.accounts()).singleElement().satisfies(account -> {
            assertThat(account.id()).isEqualTo(7L);
            assertThat(account.username()).isEqualTo("u");
            assertThat(account.status()).isEqualTo(1);
        });
        assertThat(page.nextCursor()).isEqualTo("2026-01-01T00:00:00Z|7");
        assertThat(transport.requests.getFirst().uri().getPath()).isEqualTo("/internal/backup/v1/accounts");
    }
}
