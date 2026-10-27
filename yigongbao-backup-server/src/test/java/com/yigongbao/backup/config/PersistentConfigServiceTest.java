package com.yigongbao.backup.config;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PersistentConfigServiceTest {
    @Test
    void appendsVersionAndRejectsStaleCas() {
        BkConfigMapper mapper = mock(BkConfigMapper.class);
        BkConfigEntity old = entity("backup.interval", 1, "30");
        when(mapper.selectList(any())).thenReturn(List.of(), List.of(old));
        PersistentConfigService service = new PersistentConfigService(mapper);

        ConfigService.Entry first = service.put("backup.interval", "30", 0, 9L);
        assertThat(first.version()).isEqualTo(1);
        verify(mapper).insert(any(BkConfigEntity.class));
        assertThatThrownBy(() -> service.put("backup.interval", "60", 0, 9L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void listUsesLatestVersionPerKey() {
        BkConfigMapper mapper = mock(BkConfigMapper.class);
        BkConfigEntity old = entity("a", 1, "old");
        BkConfigEntity latest = entity("a", 2, "new");
        when(mapper.selectList(any())).thenReturn(List.of(old, latest));
        assertThat(new PersistentConfigService(mapper).list()).extracting(ConfigService.Entry::value)
                .containsExactly("new");
    }

    private static BkConfigEntity entity(String key, int version, String value) {
        BkConfigEntity e = new BkConfigEntity(); e.setConfigKey(key); e.setScope("GLOBAL");
        e.setConfigVersion(version); e.setConfigValue(value); e.setChangedAt(LocalDateTime.now()); return e;
    }
}
