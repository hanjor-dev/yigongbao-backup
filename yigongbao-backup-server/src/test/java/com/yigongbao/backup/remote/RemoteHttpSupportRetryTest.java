package com.yigongbao.backup.remote;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RemoteHttpSupportRetryTest {

    @Test
    void retriesNetworkFailuresThreeTimesWithFreshNonceAndStableIdempotencyKey() {
        AtomicInteger attempts = new AtomicInteger();
        RecordingHttpClient transport = new RecordingHttpClient(request -> {
            if (attempts.incrementAndGet() < 3) throw new ConnectException("temporary network failure");
            return RecordingHttpClient.response(request, 200, "{\"data\":{}}");
        });
        RemoteHttpSupport support = new RemoteHttpSupport(transport, new ObjectMapper(),
                URI.create("http://online.example/api"), Duration.ofSeconds(5), Duration.ofHours(2),
                "shared-production-secret-32-bytes!", ExternalUriPolicy.testCompatibility(), duration -> { });

        support.data("POST", "/internal/backup/v1/candidates/search", "{}");

        assertThat(transport.requests).hasSize(3);
        assertThat(transport.requests.stream()
                .map(request -> request.headers().firstValue("X-Nonce").orElseThrow()))
                .doesNotHaveDuplicates();
        assertThat(new HashSet<>(transport.requests.stream()
                .map(request -> request.headers().firstValue("Idempotency-Key").orElseThrow())
                .toList())).hasSize(1);
    }

    @Test
    void retriesTemporaryHttpStatusesAfterFiveAndTenSeconds() {
        AtomicInteger attempts = new AtomicInteger();
        List<Duration> delays = new ArrayList<>();
        RecordingHttpClient transport = new RecordingHttpClient(request -> {
            int attempt = attempts.incrementAndGet();
            if (attempt < 3) {
                return RecordingHttpClient.response(request, 503,
                        "{\"code\":\"SERVICE_UNAVAILABLE\",\"retryable\":true}");
            }
            return RecordingHttpClient.response(request, 200, "{\"data\":{}}");
        });
        RemoteHttpSupport support = new RemoteHttpSupport(transport, new ObjectMapper(),
                URI.create("http://online.example/api"), Duration.ofSeconds(5), Duration.ofHours(2),
                "shared-production-secret-32-bytes!", ExternalUriPolicy.testCompatibility(), delays::add);

        support.data("GET", "/internal/backup/v1/orders/118/manifest", "");

        assertThat(transport.requests).hasSize(3);
        assertThat(delays).containsExactly(Duration.ofSeconds(5), Duration.ofSeconds(10));
    }

    @Test
    void reportsFailureOnlyAfterAllThreeNetworkAttemptsFail() {
        List<Duration> delays = new ArrayList<>();
        RecordingHttpClient transport = new RecordingHttpClient(request -> {
            throw new ConnectException("still unavailable");
        });
        RemoteHttpSupport support = new RemoteHttpSupport(transport, new ObjectMapper(),
                URI.create("http://online.example/api"), Duration.ofSeconds(5), Duration.ofHours(2),
                "shared-production-secret-32-bytes!", ExternalUriPolicy.testCompatibility(), delays::add);

        assertThatThrownBy(() -> support.data("GET", "/internal/backup/v1/accounts", ""))
                .isInstanceOf(RemoteCallException.class)
                .hasMessage("线上服务通信失败")
                .hasCauseInstanceOf(ConnectException.class);
        assertThat(transport.requests).hasSize(3);
        assertThat(delays).containsExactly(Duration.ofSeconds(5), Duration.ofSeconds(10));
    }

    @Test
    void doesNotRetryAuthenticationOrOtherDeterministicHttpFailures() {
        List<Duration> delays = new ArrayList<>();
        RecordingHttpClient transport = new RecordingHttpClient(request ->
                RecordingHttpClient.response(request, 401,
                        "{\"code\":\"UNAUTHORIZED\",\"retryable\":false}"));
        RemoteHttpSupport support = new RemoteHttpSupport(transport, new ObjectMapper(),
                URI.create("http://online.example/api"), Duration.ofSeconds(5), Duration.ofHours(2),
                "shared-production-secret-32-bytes!", ExternalUriPolicy.testCompatibility(), delays::add);

        assertThatThrownBy(() -> support.data("GET", "/internal/backup/v1/accounts", ""))
                .isInstanceOf(RemoteCallException.class)
                .hasMessageContaining("HTTP 401");
        assertThat(transport.requests).hasSize(1);
        assertThat(delays).isEmpty();
    }
}
