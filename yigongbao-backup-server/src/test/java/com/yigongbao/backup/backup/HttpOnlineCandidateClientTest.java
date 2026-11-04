package com.yigongbao.backup.backup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yigongbao.backup.remote.RecordingHttpClient;
import com.yigongbao.backup.remote.RemoteHttpSupport;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Flow;

import static org.assertj.core.api.Assertions.assertThat;

/** 候选增量接口契约测试，确认分页游标和订单状态可被解析。 */
class HttpOnlineCandidateClientTest {
    @Test
    void readsCandidatePage() {
        RecordingHttpClient transport = new RecordingHttpClient(request -> RecordingHttpClient.response(request, 200,
                "{\"data\":{\"items\":[{\"eventId\":1,\"orderId\":42,\"eventStatus\":\"ELIGIBLE\"}],\"nextCursor\":\"c2\",\"hasMore\":true}}"));
        RemoteHttpSupport support = new RemoteHttpSupport(transport, new ObjectMapper(), URI.create("http://online.example"),
                Duration.ofSeconds(5), "shared-production-secret-32-bytes!");
        var orderCreatedBefore = LocalDate.of(2026, 10, 29).atStartOfDay().atOffset(ZoneOffset.UTC);
        OnlineCandidateClient.Page page = new HttpOnlineCandidateClient(support, new ObjectMapper())
                .search("", 100, "", orderCreatedBefore);
        assertThat(page.items()).singleElement().extracting(OnlineCandidateClient.Candidate::orderId).isEqualTo(42L);
        assertThat(page.nextCursor()).isEqualTo("c2");
        assertThat(transport.requests.getFirst().uri().getPath()).isEqualTo("/internal/backup/v1/candidates/search");
        assertThat(requestBody(transport.requests.getFirst()))
                .contains("\"orderCreatedBefore\":\"2026-10-29T00:00Z\"");
    }

    private static String requestBody(java.net.http.HttpRequest request) {
        var bytes = new java.io.ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            @Override public void onNext(ByteBuffer item) {
                byte[] chunk = new byte[item.remaining()];
                item.get(chunk);
                bytes.writeBytes(chunk);
            }
            @Override public void onError(Throwable throwable) { throw new IllegalStateException(throwable); }
            @Override public void onComplete() { }
        });
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
