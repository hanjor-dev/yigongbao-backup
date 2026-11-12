package com.yigongbao.backup.config;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;

class PersistentConfigServiceTest {
    @Test
    void appendsVersionAndRejectsStaleCas() {
        BkConfigMapper mapper = mock(BkConfigMapper.class);
        BkConfigEntity old = entity("backup.schedule.interval-days", 1, "1");
        when(mapper.selectList(any())).thenReturn(List.of(), List.of(old));
        PersistentConfigService service = new PersistentConfigService(mapper);

        ConfigService.Entry first = service.put("backup.schedule.interval-days", "1", 0, 9L);
        assertThat(first.version()).isEqualTo(1);
        verify(mapper).insert(any(BkConfigEntity.class));
        assertThatThrownBy(() -> service.put("backup.schedule.interval-days", "2", 0, 9L))
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

    @Test
    void assignsFinalCapabilityCategoryForNewConfiguration() {
        BkConfigMapper mapper = mock(BkConfigMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of());
        PersistentConfigService service = new PersistentConfigService(mapper);

        service.put("cleanup.schedule.enabled", "true", 0, 9L);

        ArgumentCaptor<BkConfigEntity> captor = ArgumentCaptor.forClass(BkConfigEntity.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getCategoryCode()).isEqualTo("CLOUD_CLEANUP");
        assertThat(captor.getValue().getCategoryName()).isEqualTo("云端清理策略");
    }

    @Test
    void locksGlobalMutationRowBeforeProjectedCrossConfigValidationAndInsert() {
        BkConfigMapper mapper = mock(BkConfigMapper.class);
        @SuppressWarnings("unchecked") ObjectProvider<ConfigurationChangeGuard> provider = mock(ObjectProvider.class);
        ConfigurationChangeGuard guard = mock(ConfigurationChangeGuard.class);
        when(provider.getIfAvailable()).thenReturn(guard);
        when(mapper.lockGlobalMutation()).thenReturn("GLOBAL");
        when(mapper.selectList(any())).thenReturn(List.of());

        new PersistentConfigService(mapper, provider)
                .put("storage.read-lease.ttl-seconds", "120", 0, 9L);

        InOrder order = inOrder(mapper, guard);
        order.verify(mapper).lockGlobalMutation();
        order.verify(guard).validate("storage.read-lease.ttl-seconds", "120");
        order.verify(mapper).insert(any(BkConfigEntity.class));
    }

    private static BkConfigEntity entity(String key, int version, String value) {
        BkConfigEntity e = new BkConfigEntity(); e.setConfigKey(key); e.setScope("GLOBAL");
        e.setConfigVersion(version); e.setConfigValue(value); e.setChangedAt(LocalDateTime.now()); return e;
    }
}
