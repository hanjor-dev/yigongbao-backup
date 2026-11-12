package com.yigongbao.backup.cleanup;
import java.util.List;
import java.time.Instant;
/** 本地到线上清理服务的安全边界接口。 */
public interface CleanupClient {
    Evaluation evaluate(CleanupRequest request);
    Permit pollPermit(String taskId);
    default Permit pollPermit(String taskId, PermitRequest request) { return pollPermit(taskId); }
    void delete(Permit permit, String objectKey);
    record CleanupRequest(int olderThanDays, boolean dryRun, int batchSize) {
        public CleanupRequest(int olderThanDays, boolean dryRun) {
            this(olderThanDays, dryRun, 100);
        }
    }
    /** YAML permit issue 所需的完整安全证据；旧调用方仍可使用仅传 taskId 的兼容方法。 */
    record PermitRequest(int attemptNo, String snapshotHash, String objectSetHash, String fencingToken,
                         String receiptId, int receiptVersion, String verificationDigest) {}
    record Evaluation(String taskId, List<EvaluatedObject> objects, long estimatedBytes,
                      String snapshotHash, String objectSetHash) {
        public Evaluation(String taskId, List<String> objectKeys, long estimatedBytes) {
            this(taskId, objectKeys == null ? List.of() : objectKeys.stream()
                    .map(key -> new EvaluatedObject("COS", "UNKNOWN", key, null, null, "UNVERIFIED", 0L)).toList(),
                    estimatedBytes, null, null);
        }
        public Evaluation { objects = objects == null ? List.of() : List.copyOf(objects); }
        public List<String> objectKeys() { return objects.stream().map(EvaluatedObject::objectKey).toList(); }
    }
    record EvaluatedObject(String platform, String bucket, String objectKey, String versionIdentity,
                           Long sourceFileId, String sharedReferenceStatus, long versionBytes) { }
    record Permit(String permitId, String taskId, List<PermitObject> objects, String status, Instant expiresAt,
                  String objectSetHash) {
        public Permit(String permitId, String taskId, List<String> objectKeys, String status) {
            this(permitId, taskId, legacy(objectKeys), status, null, null);
        }
        public Permit(String permitId, String taskId, List<String> objectKeys, String status, Instant expiresAt) {
            this(permitId, taskId, legacy(objectKeys), status, expiresAt, null);
        }

        public Permit {
            objects = objects == null ? List.of() : List.copyOf(objects);
        }

        public List<String> objectKeys() { return objects.stream().map(PermitObject::objectKey).toList(); }

        private static List<PermitObject> legacy(List<String> keys) {
            return keys == null ? List.of() : keys.stream().map(key -> new PermitObject(key, null)).toList();
        }
    }
    record PermitObject(String objectKey, String versionIdentity) { }
}
