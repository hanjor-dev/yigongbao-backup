package com.yigongbao.backup.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yigongbao.backup.backup.ManualBackupJobHandler;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 失败订单补偿任务必须继承原任务的执行模式。 */
class AdminBackupControllerTest {
    @Test
    void retryPayloadPreservesSimulationMode() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String original = mapper.writeValueAsString(new ManualBackupJobHandler.Payload(
                List.of(1L, 2L), 2, "20261104", 7, "2026-10-28T00:00:00Z", "METADATA_SIMULATION", "D:/simulation"));

        ManualBackupJobHandler.Payload retry = AdminBackupController.retryPayload(
                mapper, original, List.of(2L));

        assertThat(retry.sourceOrderIds()).containsExactly(2L);
        assertThat(retry.executionMode()).isEqualTo("METADATA_SIMULATION");
        assertThat(retry.backupDate()).isEqualTo("20261104");
        assertThat(retry.simulationRoot()).isEqualTo("D:/simulation");
    }
}
