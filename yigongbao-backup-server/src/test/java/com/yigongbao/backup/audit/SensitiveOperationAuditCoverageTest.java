package com.yigongbao.backup.audit;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveOperationAuditCoverageTest {
    @Test
    void sensitiveControllersDeclareStableAuditActions() throws Exception {
        String root = "src/main/java/com/yigongbao/backup/";
        assertThat(Files.readString(Path.of(root + "account/AdminAccountController.java")))
                .contains("ACCOUNT_STATUS_CHANGE", "ADMIN_PASSWORD_RESET");
        assertThat(Files.readString(Path.of(root + "admin/AdminConfigController.java")))
                .contains("CONFIG_CHANGE");
        assertThat(Files.readString(Path.of(root + "admin/AdminJobController.java")))
                .contains("JOB_CANCEL", "JOB_RETRY");
        assertThat(Files.readString(Path.of(root + "order/OrderController.java")))
                .contains("ORDER_PACKAGE_DOWNLOAD");
        assertThat(Files.readString(Path.of(root + "order/SupplementController.java")))
                .contains("SUPPLEMENT_DOWNLOAD");
        assertThat(Files.readString(Path.of(root + "audit/MutationAuditInterceptor.java")))
                .contains("HTTP_MUTATION", "AUDIT_RECORDED");
    }
}
