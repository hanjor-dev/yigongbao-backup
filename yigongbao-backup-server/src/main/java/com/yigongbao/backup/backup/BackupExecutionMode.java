package com.yigongbao.backup.backup;

/** 订单备份任务的执行模式。 */
public enum BackupExecutionMode {
    /** 下载并形成正式可恢复备份。 */
    REAL,
    /** 仅读取订单与文件元数据并生成隔离的本地模拟制品。 */
    METADATA_SIMULATION
}
