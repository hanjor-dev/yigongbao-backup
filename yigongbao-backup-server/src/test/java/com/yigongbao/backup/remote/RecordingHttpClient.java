package com.yigongbao.backup.remote;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

/** 无网络单元测试用的 HttpClient；只记录请求并返回预设响应。 */
public final class RecordingHttpClient extends HttpClient {
    public final List<HttpRequest> requests = new ArrayList<>();
    private final Function<HttpRequest, HttpResponse<String>> responder;

    public RecordingHttpClient(Function<HttpRequest, HttpResponse<String>> responder) {
        this.responder = responder;
    }

    @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
    @Override public Optional<Duration> connectTimeout() { return Optional.empty(); }
    @Override public Redirect followRedirects() { return Redirect.NEVER; }
    @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
    @Override public SSLContext sslContext() { throw new UnsupportedOperationException(); }
    @Override public SSLParameters sslParameters() { return new SSLParameters(); }
    @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
    @Override public Version version() { return Version.HTTP_1_1; }
    @Override public Optional<Executor> executor() { return Optional.empty(); }

    @Override
    @SuppressWarnings("unchecked")
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        requests.add(request);
        return (HttpResponse<T>) responder.apply(request);
    }

    @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) { throw new UnsupportedOperationException(); }
    @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler, HttpResponse.PushPromiseHandler<T> pushPromiseHandler) { throw new UnsupportedOperationException(); }

    public static HttpResponse<String> response(HttpRequest request, int status, String body) {
        return new HttpResponse<>() {
            public int statusCode() { return status; }
            public HttpRequest request() { return request; }
            public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
            public java.net.http.HttpHeaders headers() { return java.net.http.HttpHeaders.of(java.util.Map.of(), (a,b) -> true); }
            public String body() { return body; }
            public Optional<javax.net.ssl.SSLSession> sslSession() { return Optional.empty(); }
            public URI uri() { return request.uri(); }
            public Version version() { return Version.HTTP_1_1; }
        };
    }
}
