package com.yigongbao.backup.account;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BackupStpInterfaceTest {
    @Test
    void disabledAccountHasNoRolesEvenWithExistingToken() {
        BkUserMapper mapper = mock(BkUserMapper.class);
        BkUserEntity user = new BkUserEntity();
        user.setRoleCode("ADMIN");
        user.setEnabledStatus("DISABLED");
        when(mapper.selectById(7L)).thenReturn(user);

        assertThat(new BackupStpInterface(mapper).getRoleList(7L, "login")).isEmpty();
    }
}
