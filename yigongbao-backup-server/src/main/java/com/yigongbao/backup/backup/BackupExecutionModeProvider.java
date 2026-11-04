package com.yigongbao.backup.backup;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

/**
 * 从部署环境读取备份执行模式，并在应用启动阶段校验高风险组合。
 * 执行模式故意不放入数据库配置，避免管理员在运行中把模拟任务切换成真实下载。
 */
@Component
public class BackupExecutionModeProvider {
    private final BackupExecutionMode mode;
    private final Path simulationRoot;

    /** 解析并校验只读的部署环境配置。 */
    public BackupExecutionModeProvider(Environment environment) {
        String rawMode = environment.getProperty("backup.execution.mode", "REAL");
        try {
            this.mode = BackupExecutionMode.valueOf(rawMode.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("不支持的备份执行模式: " + rawMode, invalid);
        }
        String rawRoot = environment.getProperty("backup.simulation.root");
        this.simulationRoot = rawRoot == null || rawRoot.isBlank()
                ? null : Path.of(rawRoot).toAbsolutePath().normalize();
        validate(environment);
    }

    private void validate(Environment environment) {
        if (mode != BackupExecutionMode.METADATA_SIMULATION) return;
        if (simulationRoot == null) {
            throw new IllegalStateException("元数据模拟模式必须配置 BACKUP_SIMULATION_ROOT");
        }
        if (Arrays.stream(environment.getActiveProfiles()).anyMatch("prod"::equalsIgnoreCase)) {
            throw new IllegalStateException("生产环境禁止启用元数据模拟备份模式");
        }
    }

    /** 返回当前部署的执行模式。 */
    public BackupExecutionMode mode() {
        return mode;
    }

    /** 返回模拟制品根目录；真实模式下可能为空。 */
    public Path simulationRoot() {
        return simulationRoot;
    }
}
