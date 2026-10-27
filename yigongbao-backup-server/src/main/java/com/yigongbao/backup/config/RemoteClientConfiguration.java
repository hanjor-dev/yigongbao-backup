package com.yigongbao.backup.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yigongbao.backup.backup.HttpCosObjectClient;
import com.yigongbao.backup.backup.HttpOnlineManifestClient;
import com.yigongbao.backup.backup.HttpOnlineCandidateClient;
import com.yigongbao.backup.backup.OnlineCandidateClient;
import com.yigongbao.backup.account.HttpRemoteAccountClient;
import com.yigongbao.backup.cleanup.CleanupClient;
import com.yigongbao.backup.cleanup.HttpCleanupClient;
import com.yigongbao.backup.remote.RemoteHttpSupport;
import com.yigongbao.backup.restore.HttpOnlineRestoreClient;
import com.yigongbao.backup.restore.OnlineRestoreClient;
import com.yigongbao.backup.restore.RestoreEvidenceStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

/** 线上适配器装配。默认关闭，避免未配置时误连线上或误触发清理。 */
@Configuration
public class RemoteClientConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "backup.online", name = "enabled", havingValue = "true")
    RemoteHttpSupport remoteHttpSupport(
            @Value("${backup.online.base-url:}") String baseUrl,
            @Value("${backup.online.connect-timeout:PT10S}") Duration connectTimeout,
            @Value("${backup.online.request-timeout:PT60S}") Duration requestTimeout,
            @Value("${backup.online.auth-mode:NONE}") String authMode,
            @Value("${backup.online.key-id:}") String keyId,
            @Value("${backup.online.key-secret:}") String keySecret,
            @Value("${backup.online.key-secret-ref:}") String keySecretRef,
            @Value("${backup.online.bearer-token:}") String bearerToken,
            @Value("${backup.online.scope:BACKUP}") String scope,
            ObjectMapper mapper) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("线上适配器已启用但未配置backup.online.base-url");
        }
        String secret = keySecret;
        if ((secret == null || secret.isBlank()) && keySecretRef != null && !keySecretRef.isBlank()) {
            secret = System.getProperty(keySecretRef);
            if (secret == null || secret.isBlank()) {
                secret = System.getenv(keySecretRef);
            }
        }
        HttpClient client = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        return new RemoteHttpSupport(client, mapper, URI.create(baseUrl), requestTimeout,
                authMode, keyId, secret, bearerToken, scope);
    }

    @Bean
    @ConditionalOnProperty(prefix = "backup.online", name = "enabled", havingValue = "true")
    HttpOnlineRestoreClient onlineRestoreClient(RemoteHttpSupport http, ObjectMapper mapper,
                                                 RestoreEvidenceStore evidenceStore) {
        return new HttpOnlineRestoreClient(http, mapper, evidenceStore);
    }

    @Bean
    @ConditionalOnProperty(prefix = "backup.online", name = "enabled", havingValue = "true")
    HttpCleanupClient onlineCleanupClient(RemoteHttpSupport http, ObjectMapper mapper) {
        return new HttpCleanupClient(http, mapper);
    }

    @Bean
    @ConditionalOnProperty(prefix = "backup.online", name = "enabled", havingValue = "true")
    HttpOnlineManifestClient onlineManifestClient(RemoteHttpSupport http, ObjectMapper mapper) {
        return new HttpOnlineManifestClient(http, mapper);
    }

    @Bean
    @ConditionalOnProperty(prefix = "backup.online", name = "enabled", havingValue = "true")
    OnlineCandidateClient onlineCandidateClient(RemoteHttpSupport http, ObjectMapper mapper) {
        return new HttpOnlineCandidateClient(http, mapper);
    }

    @Bean
    @ConditionalOnProperty(prefix = "backup.online", name = "enabled", havingValue = "true")
    HttpCosObjectClient cosObjectClient(RemoteHttpSupport http) {
        return new HttpCosObjectClient(http);
    }

    @Bean
    @ConditionalOnProperty(prefix = "backup.online", name = "enabled", havingValue = "true")
    HttpRemoteAccountClient remoteAccountClient(RemoteHttpSupport http) {
        return new HttpRemoteAccountClient(http);
    }
}
