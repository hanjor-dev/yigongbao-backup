package com.yigongbao.backup.account;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class AccountSyncServiceTest {
    @Test
    void remoteSyncDoesNotOverwriteLocalPasswordOrStatus() {
        InMemoryBkUserRepository repository = new InMemoryBkUserRepository();
        AccountSyncService service = new AccountSyncService(repository);
        BkUser local = new BkUser();
        local.setSourceUserId(1L);
        local.setUsername("alice");
        local.setPasswordHash(new BCryptPasswordEncoder().encode("local-secret"));
        local.setEnabledStatus(1);
        repository.save(local);

        service.merge(new RemoteAccount(1L, "alice-new", "remote-hash", 0,
                "Alice", "138", "a@example.com", LocalDateTime.now()));

        assertThat(local.getUsername()).isEqualTo("alice-new");
        assertThat(local.getPasswordHash()).isNotEqualTo("remote-hash");
        assertThat(local.getEnabledStatus()).isEqualTo(1);
        assertThat(service.verifyPassword(local, "local-secret")).isTrue();
    }

    @Test
    void locksLocalAccountAfterFiveFailedPasswords() {
        InMemoryBkUserRepository repository = new InMemoryBkUserRepository();
        AccountSyncService service = new AccountSyncService(repository);
        BkUser local = new BkUser();
        local.setSourceUserId(2L);
        local.setUsername("locked");
        local.setPasswordHash(new BCryptPasswordEncoder().encode("local-secret"));
        local.setEnabledStatus(1);
        repository.save(local);

        for (int i = 0; i < 5; i++) {
            assertThat(service.verifyPassword(local, "wrong-secret")).isFalse();
        }

        assertThat(local.getFailedLoginCount()).isEqualTo(5);
        assertThat(local.getLockedUntil()).isNotNull();
        assertThat(service.verifyPassword(local, "local-secret")).isFalse();
    }
}
