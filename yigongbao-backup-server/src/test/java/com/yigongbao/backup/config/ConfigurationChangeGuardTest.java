package com.yigongbao.backup.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConfigurationChangeGuardTest {
    @Test
    void refusesToEnableOnlineCommunicationWithoutBaseUrl() {
        PersistentConfigService configs = mock(PersistentConfigService.class);
        when(configs.get("online.base-url")).thenReturn(entry("online.base-url", ""));
        ConfigurationChangeGuard guard = new ConfigurationChangeGuard(configs, new MockEnvironment());

        assertThatThrownBy(() -> guard.validate("online.enabled", "true"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("线上系统地址");
    }

    @Test
    void refusesToEnableOnlineCommunicationWithoutSharedSecret() {
        PersistentConfigService configs = mock(PersistentConfigService.class);
        when(configs.get("online.base-url")).thenReturn(entry("online.base-url", "https://online.example"));
        ConfigurationChangeGuard guard = new ConfigurationChangeGuard(configs, new MockEnvironment());

        assertThatThrownBy(() -> guard.validate("online.enabled", "true"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BACKUP_SHARED_HMAC_SECRET");
    }

    @Test
    void refusesToEnableOnlineCommunicationWithShortSharedSecret() {
        PersistentConfigService configs = mock(PersistentConfigService.class);
        when(configs.get("online.base-url")).thenReturn(entry("online.base-url", "https://online.example"));
        MockEnvironment environment = new MockEnvironment()
                .withProperty("BACKUP_SHARED_HMAC_SECRET", "too-short");
        ConfigurationChangeGuard guard = new ConfigurationChangeGuard(configs, environment);

        assertThatThrownBy(() -> guard.validate("online.enabled", "true"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32");
    }

    @Test
    void refusesHeartbeatThatCannotRefreshLeaseBeforeHalfTtl() {
        PersistentConfigService configs = mock(PersistentConfigService.class);
        when(configs.get("storage.read-lease.ttl-seconds")).thenReturn(entry("storage.read-lease.ttl-seconds", "60"));
        ConfigurationChangeGuard guard = new ConfigurationChangeGuard(configs, new MockEnvironment());

        assertThatThrownBy(() -> guard.validate("storage.read-lease.heartbeat-seconds", "30"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("一半");
    }

    @Test
    void allowsPlainHttpForRemoteOnlineAddress() {
        PersistentConfigService configs = mock(PersistentConfigService.class);
        when(configs.get("online.enabled")).thenReturn(entry("online.enabled", "true"));
        MockEnvironment environment = new MockEnvironment()
                .withProperty("BACKUP_SHARED_HMAC_SECRET", "shared-production-secret-32-bytes!");
        ConfigurationChangeGuard guard = new ConfigurationChangeGuard(configs, environment);

        guard.validate("online.base-url", "http://online.example");
    }

    private static ConfigService.Entry entry(String key, String value) {
        return new ConfigService.Entry(key, value, 1, 1L, Instant.now());
    }
}
