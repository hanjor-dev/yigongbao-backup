package com.yigongbao.backup.account;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AuthSessionContractTest {

    @Test
    void passwordChangesRevokeAllExistingSessionsForTheAccount() throws Exception {
        String selfService = Files.readString(Path.of("src/main/java/com/yigongbao/backup/account/AuthController.java"));
        String adminService = Files.readString(Path.of("src/main/java/com/yigongbao/backup/account/AdminAccountController.java"));

        assertThat(selfService).contains("StpUtil.kickout(actor)");
        assertThat(adminService).contains("StpUtil.kickout(id)");
    }
}
