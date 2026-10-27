package com.yigongbao.backup.restore;

import java.util.List;

/**
 * 恢复会话与上传证据的持久化边界。
 *
 * <p>线上恢复是跨本地数据库、COS 和线上业务库的长事务，不能只依赖进程内
 * Map 保存上传结果。实现必须保证同一会话、同一资产重复记录时幂等。</p>
 */
public interface RestoreEvidenceStore {
    void prepared(PreparedSession session);

    void uploaded(String sessionId, UploadEvidence evidence);

    /** 上传响应未知或失败时保存不完整证据，后续按孤儿对象处理。 */
    default void uploadFailed(String sessionId, UploadEvidence evidence) { uploaded(sessionId, evidence); }

    /** 将会话内已记录的对象标记为待人工确认的孤儿对象。 */
    default void markOrphans(String sessionId) { }

    List<UploadEvidence> uploads(String sessionId);

    void status(String sessionId, String status);

    default String currentStatus(String sessionId) { return null; }

    /** 未接入数据库时的安全兼容实现；线上客户端仍会把证据发送给线上系统。 */
    static RestoreEvidenceStore noOp() {
        return new RestoreEvidenceStore() {
            @Override public void prepared(PreparedSession session) { }
            @Override public void uploaded(String sessionId, UploadEvidence evidence) { }
            @Override public List<UploadEvidence> uploads(String sessionId) { return List.of(); }
            @Override public void status(String sessionId, String status) { }
            @Override public String currentStatus(String sessionId) { return null; }
        };
    }

    record PreparedSession(String sessionId, String orderId, String manifestRevision,
                           String signedPlanDigest, String relationSetHash, String conflictPolicy) {
    }

    record UploadEvidence(String assetIdentity, String objectKey, String versionId,
                          String etag, long size, String sha256) {
    }
}
