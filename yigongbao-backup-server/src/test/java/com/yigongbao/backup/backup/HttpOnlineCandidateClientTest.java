package com.yigongbao.backup.backup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yigongbao.backup.remote.RecordingHttpClient;
import com.yigongbao.backup.remote.RemoteHttpSupport;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** 候选增量接口契约测试，确认分页游标和订单状态可被解析。 */
class HttpOnlineCandidateClientTest {
    @Test
    void readsCandidatePage() {
        RecordingHttpClient transport = new RecordingHttpClient(request -> RecordingHttpClient.response(request, 200,
                "{\"data\":{\"items\":[{\"eventId\":1,\"orderId\":42,\"eventStatus\":\"ELIGIBLE\"}],\"nextCursor\":\"c2\",\"hasMore\":true}}"));
        RemoteHttpSupport support = new RemoteHttpSupport(transport, new ObjectMapper(), URI.create("http://online.example"),
                Duration.ofSeconds(5), "NONE", "", "", "");
        OnlineCandidateClient.Page page = new HttpOnlineCandidateClient(support, new ObjectMapper()).search("", 100);
        assertThat(page.items()).singleElement().extracting(OnlineCandidateClient.Candidate::orderId).isEqualTo(42L);
        assertThat(page.nextCursor()).isEqualTo("c2");
        assertThat(transport.requests.getFirst().uri().getPath()).isEqualTo("/internal/backup/v1/candidates/search");
    }
}
