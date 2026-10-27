package com.yigongbao.backup.cleanup;
import java.util.List;
import java.time.Instant;
/** 本地到线上清理服务的安全边界接口。 */
public interface CleanupClient {
    Evaluation evaluate(CleanupRequest request);
    Permit pollPermit(String taskId);
    void delete(Permit permit, String objectKey);
    record CleanupRequest(int olderThanDays, boolean dryRun) {}
    /** YAML permit issue 所需的完整安全证据；旧调用方仍可使用仅传 taskId 的兼容方法。 */
    record PermitRequest(int attemptNo, String snapshotHash, String objectSetHash, String fencingToken,
                         String receiptId, int receiptVersion, String verificationDigest) {}
    record Evaluation(String taskId, List<String> objectKeys, long estimatedBytes) {}
    record Permit(String permitId, String taskId, List<String> objectKeys, String status, Instant expiresAt) {
        public Permit(String permitId, String taskId, List<String> objectKeys, String status) {
            this(permitId, taskId, objectKeys, status, null);
        }

        public Permit {
            objectKeys = objectKeys == null ? List.of() : List.copyOf(objectKeys);
        }
    }
}
