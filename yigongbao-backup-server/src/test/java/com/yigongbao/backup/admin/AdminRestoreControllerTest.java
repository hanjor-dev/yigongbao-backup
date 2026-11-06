package com.yigongbao.backup.admin;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证用户主动恢复与同一 HTTP 请求重发的幂等边界。 */
class AdminRestoreControllerTest {
    @Test
    void generatesDistinctKeysForIndependentRestoreSubmissions() {
        String first = AdminRestoreController.restoreRequestKey(null);
        String second = AdminRestoreController.restoreRequestKey(null);

        assertThat(first).isNotEqualTo(second);
        assertThat(AdminRestoreController.restoreRequestKey("request-1")).isEqualTo("request-1");
    }
}
