package com.yigongbao.backup.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OnlineConnectionSettingsProviderTest {
    @Test
    void runtimeAcceptsPersistedRemoteHttpUrl() {
        RuntimeConfigService configs = mock(RuntimeConfigService.class);
        when(configs.getBoolean("online.enabled", false)).thenReturn(true);
        when(configs.require("online.base-url")).thenReturn("http://online.example");
        MockEnvironment environment = new MockEnvironment()
                .withProperty("BACKUP_SHARED_HMAC_SECRET", "shared-production-secret-32-bytes!")
                .withProperty("BACKUP_EXTERNAL_ALLOWED_HOSTS", "cos.example.com");

        OnlineConnectionSettingsProvider.Settings settings =
                new OnlineConnectionSettingsProvider(configs, environment).current();

        assertThat(settings.baseUri()).hasToString("http://online.example");
    }

    @Test
    void rejectsEnabledOnlineConnectionWhenExternalHostAllowlistIsMissing() {
        RuntimeConfigService configs = mock(RuntimeConfigService.class);
        when(configs.getBoolean("online.enabled", false)).thenReturn(true);
        when(configs.require("online.base-url")).thenReturn("https://online.example");
        MockEnvironment environment = new MockEnvironment()
                .withProperty("BACKUP_SHARED_HMAC_SECRET", "shared-production-secret-32-bytes!");

        assertThatThrownBy(() -> new OnlineConnectionSettingsProvider(configs, environment).current())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BACKUP_EXTERNAL_ALLOWED_HOSTS")
                .hasMessageContaining("对象存储");
    }
}
