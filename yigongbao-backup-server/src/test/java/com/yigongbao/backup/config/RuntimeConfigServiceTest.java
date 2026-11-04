package com.yigongbao.backup.config;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeConfigServiceTest {
    @Test
    void requiredValueFailsClearlyWhenDatabaseConfigurationIsMissing() {
        PersistentConfigService configs = mock(PersistentConfigService.class);
        RuntimeConfigService service = new RuntimeConfigService(configs);

        assertThatThrownBy(() -> service.require("storage.hot-root"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("storage.hot-root");
    }

    @Test
    void typedReadsDoNotSilentlyUseCodeDefaultsWhenDatabaseRowIsMissing() {
        PersistentConfigService configs = mock(PersistentConfigService.class);
        RuntimeConfigService service = new RuntimeConfigService(configs);

        assertThatThrownBy(() -> service.getBoolean("online.enabled", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("online.enabled");
    }

    @Test
    void requiredValueComesFromDatabase() {
        PersistentConfigService configs = mock(PersistentConfigService.class);
        when(configs.get("storage.hot-root")).thenReturn(new ConfigService.Entry(
                "storage.hot-root", "D:/backup", 1, 1L, Instant.now()));
        RuntimeConfigService service = new RuntimeConfigService(configs);

        assertThat(service.require("storage.hot-root")).isEqualTo("D:/backup");
    }
}
