package com.yigongbao.backup.restore;
import java.io.InputStream; import java.io.IOException; import java.util.List; import java.util.Map;
/** 本地到线上恢复接口抽象，提交前必须完成订单全部资产上传。 */
public interface OnlineRestoreClient {
    Session prepare(String orderId);
    /** 带不可变清单摘要的 prepare；旧实现默认退回原有无参数方法。 */
    default Session prepare(String orderId, RestoreRequest request) {
        return prepare(orderId);
    }
    void upload(Session session, String assetName, InputStream data) throws IOException;
    void commit(Session session);
    void abort(Session session);

    /** 网络结果不确定时查询线上权威会话状态；旧实现返回 UNKNOWN 以禁止自动误判成功。 */
    default SessionStatus query(String sessionId) {
        return new SessionStatus(sessionId, "UNKNOWN");
    }

    /** 本地会话状态，用于重启后避免重复提交已完成会话。 */
    default String localStatus(String sessionId) { return null; }

    /** uploadTargets 通常是线上签发的 COS 预签名 URL；保留两参数构造器兼容旧实现。 */
    record Session(String id, List<String> expectedAssets, Map<String, String> uploadTargets,
                   Map<String, String> uploadObjectKeys, String signedPlanDigest, String status) {
        public Session(String id, List<String> expectedAssets) {
            this(id, expectedAssets, Map.of(), Map.of(), null, "PREPARED");
        }
        public Session(String id, List<String> expectedAssets, Map<String, String> uploadTargets,
                       String signedPlanDigest) {
            this(id, expectedAssets, uploadTargets, Map.of(), signedPlanDigest, "PREPARED");
        }

        public Session {
            expectedAssets = expectedAssets == null ? List.of() : List.copyOf(expectedAssets);
            uploadTargets = uploadTargets == null ? Map.of() : Map.copyOf(uploadTargets);
            uploadObjectKeys = uploadObjectKeys == null ? Map.of() : Map.copyOf(uploadObjectKeys);
        }
    }

    /** ZIP 内固化的文件资产与关联快照；线上以它作为历史恢复计划的权威输入。 */
    record RestoreAsset(String assetIdentity, String originalFilename, long size, String sha256,
                        String platform, String sourceObjectKey, List<Map<String, Object>> relations) {
        public RestoreAsset {
            relations = relations == null ? List.of() : List.copyOf(relations);
        }
    }

    record RestoreRequest(String manifestRevision, String manifestSha256, String conflictPolicy,
                          String requestDigest, String assetSetDigest, String relationSetHash,
                          List<RestoreAsset> assets) {
        public RestoreRequest(String manifestRevision, String manifestSha256, String conflictPolicy,
                              String requestDigest, String assetSetDigest, String relationSetHash) {
            this(manifestRevision, manifestSha256, conflictPolicy, requestDigest, assetSetDigest, relationSetHash, List.of());
        }
        public RestoreRequest {
            assets = assets == null ? List.of() : List.copyOf(assets);
        }
    }

    record SessionStatus(String sessionId, String status) {}
}
