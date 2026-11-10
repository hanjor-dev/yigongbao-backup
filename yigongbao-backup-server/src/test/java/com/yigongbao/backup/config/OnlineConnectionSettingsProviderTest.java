package com.yigongbao.backup.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OnlineConnectionSettingsProviderTest {
    @Test
    void runtimeAcceptsPersistedRemoteHttpUrl() {
        RuntimeConfigService configs = mock(RuntimeConfigService.class);
        when(configs.getBoolean("online.enabled", false)).thenReturn(true);
        when(configs.require("online.base-url")).thenReturn("http://online.example");
        MockEnvironment environment = new MockEnvironment()
                .withProperty("BACKUP_SHARED_HMAC_SECRET", "shared-production-secret-32-bytes!");

        OnlineConnectionSettingsProvider.Settings settings =
                new OnlineConnectionSettingsProvider(configs, environment).current();

        assertThat(settings.baseUri()).hasToString("http://online.example");
    }
}
