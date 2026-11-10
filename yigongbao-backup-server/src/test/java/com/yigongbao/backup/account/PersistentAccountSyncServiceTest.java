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
        verify(fields).ensureRemote(10L, BkUserFieldControlService.PASSWORD_FIELD);
        verify(fields).ensureRemote(10L, BkUserFieldControlService.STATUS_FIELD);
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

    @Test
    void remoteOwnedPasswordAndStatusFollowNewerRemoteRecord() {
        BkUserMapper mapper = mock(BkUserMapper.class);
        BkUserFieldControlService fields = mock(BkUserFieldControlService.class);
        BkUserEntity local = new BkUserEntity();
        local.setId(11L); local.setSourceUserId(7L); local.setUsername("u");
        local.setPasswordHash("$2a$10$old"); local.setEnabledStatus("ENABLED");
        local.setSourceUpdatedAt(LocalDateTime.of(2026, 10, 29, 1, 0));
        when(mapper.findBySourceUserId(7L)).thenReturn(local);
        when(fields.isRemote(11L, BkUserFieldControlService.PASSWORD_FIELD)).thenReturn(true);
        when(fields.isRemote(11L, BkUserFieldControlService.STATUS_FIELD)).thenReturn(true);

        new PersistentAccountSyncService(mapper, fields).merge(new RemoteAccount(7L, "u", "$2a$10$new", 0,
                "U", null, null, LocalDateTime.of(2026, 10, 29, 2, 0), false));

        assertThat(local.getPasswordHash()).isEqualTo("$2a$10$new");
        assertThat(local.getEnabledStatus()).isEqualTo("DISABLED");
        verify(mapper).updateById(local);
    }

    @Test
    void failedLoginUsesAtomicDatabaseIncrement() {
        BkUserMapper mapper = mock(BkUserMapper.class);
        BkUserEntity user = new BkUserEntity();
        user.setId(3L); user.setEnabledStatus("ENABLED"); user.setPasswordHash("$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi");
        when(mapper.findByUsername("u")).thenReturn(user);

        assertThat(new PersistentAccountSyncService(mapper, mock(BkUserFieldControlService.class)).verify("u", "wrong")).isFalse();

        verify(mapper).recordLoginFailure(3L);
        verify(mapper, never()).updateById(user);
    }
}
