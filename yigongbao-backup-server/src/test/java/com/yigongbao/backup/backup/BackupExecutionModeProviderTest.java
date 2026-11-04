package com.yigongbao.backup.backup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 备份执行模式环境配置测试。 */
class BackupExecutionModeProviderTest {

    @TempDir
    Path tempDir;

    @Test
    void defaultsToRealMode() {
        BackupExecutionModeProvider provider = new BackupExecutionModeProvider(new MockEnvironment());
        assertEquals(BackupExecutionMode.REAL, provider.mode());
    }

    @Test
    void readsSimulationModeAndRoot() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("backup.execution.mode", "METADATA_SIMULATION")
                .withProperty("backup.simulation.root", tempDir.toString());
        BackupExecutionModeProvider provider = new BackupExecutionModeProvider(environment);
        assertEquals(BackupExecutionMode.METADATA_SIMULATION, provider.mode());
        assertEquals(tempDir.toAbsolutePath().normalize(), provider.simulationRoot());
    }

    @Test
    void rejectsUnknownMode() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("backup.execution.mode", "UNKNOWN");
        assertThrows(IllegalStateException.class, () -> new BackupExecutionModeProvider(environment));
    }

    @Test
    void rejectsSimulationWithoutRoot() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("backup.execution.mode", "METADATA_SIMULATION")
                .withProperty("backup.simulation.root", " ");
        assertThrows(IllegalStateException.class, () -> new BackupExecutionModeProvider(environment));
    }

    @Test
    void rejectsSimulationInProductionProfile() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("backup.execution.mode", "METADATA_SIMULATION")
                .withProperty("backup.simulation.root", tempDir.toString());
        environment.setActiveProfiles("prod");
        assertThrows(IllegalStateException.class, () -> new BackupExecutionModeProvider(environment));
    }
}
