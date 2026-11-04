package com.yigongbao.backup.account;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** 持久化账号合并规则测试。 */
class PersistentAccountSyncServiceTest {

    @Test
    void firstDeletedAccountIsProvisionedDisabled() {
        BkUserMapper mapper = mock(BkUserMapper.class);
        BkUserFieldControlService fields = mock(BkUserFieldControlService.class);
        when(mapper.insert(any(BkUserEntity.class))).thenAnswer(invocation -> {
            BkUserEntity entity = invocation.getArgument(0);
            entity.setId(10L);
            return 1;
        });
        PersistentAccountSyncService service = new PersistentAccountSyncService(mapper, fields);

        BkUserEntity result = service.merge(new RemoteAccount(7L, "deleted-user", "$2a$10$stored-hash", 1,
                "Deleted", null, null, LocalDateTime.of(2026, 10, 29, 1, 0), true));

        assertThat(result.getEnabledStatus()).isEqualTo("DISABLED");
    }

    @Test
    void staleRemoteRecordCannotOverwriteNewerSourceProfile() {
        BkUserMapper mapper = mock(BkUserMapper.class);
        BkUserEntity local = new BkUserEntity();
        local.setId(11L);
        local.setSourceUserId(7L);
        local.setUsername("new-name");
        local.setRealName("New Name");
        local.setPasswordHash("$2a$10$local-hash");
        local.setEnabledStatus("ENABLED");
        local.setSourceUpdatedAt(LocalDateTime.of(2026, 10, 29, 2, 0));
        when(mapper.findBySourceUserId(7L)).thenReturn(local);
        PersistentAccountSyncService service = new PersistentAccountSyncService(
                mapper, mock(BkUserFieldControlService.class));

        service.merge(new RemoteAccount(7L, "old-name", "$2a$10$remote-hash", 0,
                "Old Name", null, null, LocalDateTime.of(2026, 10, 29, 1, 0), false));

        assertThat(local.getUsername()).isEqualTo("new-name");
        assertThat(local.getRealName()).isEqualTo("New Name");
        verify(mapper, never()).updateById(any(BkUserEntity.class));
    }
}
