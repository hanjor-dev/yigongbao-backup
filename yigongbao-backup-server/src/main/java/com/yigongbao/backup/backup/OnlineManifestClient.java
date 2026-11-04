package com.yigongbao.backup.backup;

/** 线上清单及备份凭证接口抽象。 */
public interface OnlineManifestClient {
    BackupManifest fetch(String orderId);
    default void confirm(String orderId, BackupManifest manifest) { }
    default void submitReceipt(String orderId, BackupManifest manifest, OrderBackupPipeline.Result result,
                               String idempotencyKey, int receiptVersion) { }
}
