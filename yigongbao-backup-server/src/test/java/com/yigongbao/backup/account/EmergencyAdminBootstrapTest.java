package com.yigongbao.backup.account;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EmergencyAdminBootstrapTest {
    @Test
    void existingEmergencyAdminNeedsNoBootstrapSecret() throws Exception {
        BkUserMapper mapper = mock(BkUserMapper.class);
        when(mapper.findEmergencyAdmin()).thenReturn(new BkUserEntity());
        new EmergencyAdminBootstrap(mapper, new MockEnvironment()).run();
        verify(mapper, never()).insert(any(BkUserEntity.class));
    }

    @Test
    void legacyFixedPasswordMustBeRotatedBeforeStartup() {
        BkUserMapper mapper = mock(BkUserMapper.class);
        BkUserEntity legacy = new BkUserEntity();
        legacy.setPasswordHash("$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi");
        when(mapper.findEmergencyAdmin()).thenReturn(legacy);

        assertThatThrownBy(() -> new EmergencyAdminBootstrap(mapper, new MockEnvironment()).run())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("BACKUP_BOOTSTRAP_ADMIN_PASSWORD");
    }

    @Test
    void firstStartupRefusesMissingBootstrapSecret() {
        BkUserMapper mapper = mock(BkUserMapper.class);
        assertThatThrownBy(() -> new EmergencyAdminBootstrap(mapper, new MockEnvironment()).run())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("BACKUP_BOOTSTRAP_ADMIN_PASSWORD");
    }

    @Test
    void firstStartupCreatesAdminWithUniqueSecret() throws Exception {
        BkUserMapper mapper = mock(BkUserMapper.class);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("BACKUP_BOOTSTRAP_ADMIN_PASSWORD", "unique-bootstrap-secret-2026")
                .withProperty("BACKUP_BOOTSTRAP_ADMIN_USERNAME", "ops-admin");
        new EmergencyAdminBootstrap(mapper, environment).run();
        var captor = org.mockito.ArgumentCaptor.forClass(BkUserEntity.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getUsername()).isEqualTo("ops-admin");
        assertThat(captor.getValue().getPasswordHash()).startsWith("$2").doesNotContain("unique-bootstrap-secret-2026");
        assertThat(captor.getValue().getEmergencyAdmin()).isTrue();
    }
}
