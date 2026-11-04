package com.yigongbao.backup.backup;

import java.nio.file.Path;

/** 单个订单的元数据模拟制品结果；directory 仅用于诊断，最终持久化制品为 ZIP。 */
public record SimulationArtifactResult(Path directory, int expectedFileCount, long expectedBytes,
                                       String storageKey, String packageSha256, long packageSize,
                                       java.util.Map<String, String> placeholderHashes,
                                       java.util.Map<String, String> packageEntryNames) {
    /** 将模拟包转换为统一的订单包证据结果。 */
    public OrderBackupPipeline.Result packageResult() {
        return new OrderBackupPipeline.Result(storageKey, packageSha256, packageSize,
                placeholderHashes, packageEntryNames);
    }
}
