package com.yigongbao.backup.backup;

/** 线上清单及备份凭证接口抽象。 */
public interface OnlineManifestClient {
    BackupManifest fetch(String orderId);
    default BackupManifest fetchDegraded(String orderId) {
        throw new UnsupportedOperationException("当前线上清单客户端不支持降级备份");
    }
    default void confirm(String orderId, BackupManifest manifest) { }
    default void submitReceipt(String orderId, BackupManifest manifest, OrderBackupPipeline.Result result,
                               String idempotencyKey, int receiptVersion) { }
    default void submitReceipt(String orderId, BackupManifest manifest, OrderBackupPipeline.Result result,
                               String idempotencyKey, int receiptVersion, java.time.Instant completedAt) {
        submitReceipt(orderId, manifest, result, idempotencyKey, receiptVersion);
    }
}
