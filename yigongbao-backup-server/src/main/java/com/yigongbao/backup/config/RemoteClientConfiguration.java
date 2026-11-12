package com.yigongbao.backup.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yigongbao.backup.account.HttpRemoteAccountClient;
import com.yigongbao.backup.backup.HttpCosObjectClient;
import com.yigongbao.backup.backup.HttpOnlineCandidateClient;
import com.yigongbao.backup.backup.HttpOnlineManifestClient;
import com.yigongbao.backup.backup.OnlineCandidateClient;
import com.yigongbao.backup.cleanup.HttpCleanupClient;
import com.yigongbao.backup.remote.RemoteHttpSupport;
import com.yigongbao.backup.restore.HttpOnlineRestoreClient;
import com.yigongbao.backup.restore.RestoreEvidenceStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 线上适配器装配。
 * 客户端始终存在，但每次调用都会读取数据库连接策略；总开关关闭时调用会明确失败。
 */
@Configuration
public class RemoteClientConfiguration {
    @Bean
    RemoteHttpSupport remoteHttpSupport(OnlineConnectionSettingsProvider settings, ObjectMapper mapper) {
        return new RemoteHttpSupport(mapper, () -> {
            OnlineConnectionSettingsProvider.Settings current = settings.current();
            return new RemoteHttpSupport.OnlineSettings(current.baseUri(), current.connectTimeout(),
                    current.requestTimeout(), current.fileTransferTimeout(), current.sharedSecret(),
                    current.externalAllowedHosts());
        });
    }

    @Bean
    HttpOnlineRestoreClient onlineRestoreClient(RemoteHttpSupport http, ObjectMapper mapper,
                                                 RestoreEvidenceStore evidenceStore) {
        return new HttpOnlineRestoreClient(http, mapper, evidenceStore);
    }

    @Bean
    HttpCleanupClient onlineCleanupClient(RemoteHttpSupport http, ObjectMapper mapper) {
        return new HttpCleanupClient(http, mapper);
    }

    @Bean
    HttpOnlineManifestClient onlineManifestClient(RemoteHttpSupport http, ObjectMapper mapper) {
        return new HttpOnlineManifestClient(http, mapper);
    }

    @Bean
    OnlineCandidateClient onlineCandidateClient(RemoteHttpSupport http, ObjectMapper mapper) {
        return new HttpOnlineCandidateClient(http, mapper);
    }

    @Bean
    HttpCosObjectClient cosObjectClient(RemoteHttpSupport http) {
        return new HttpCosObjectClient(http);
    }

    @Bean
    HttpRemoteAccountClient remoteAccountClient(RemoteHttpSupport http) {
        return new HttpRemoteAccountClient(http);
    }
}
