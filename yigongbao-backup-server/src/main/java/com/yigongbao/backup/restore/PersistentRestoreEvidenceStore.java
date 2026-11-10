package com.yigongbao.backup.restore;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.time.LocalDateTime;

/**
 * 使用 bk_restore_session/bk_restore_file 保存恢复证据。
 *
 * <p>同一远程会话和资产身份重复回调只更新同一行，不会在本地生成重复证据。
 * 线上接口失败时这些记录仍保留，便于运维重试或审计。</p>
 */
@Service
@RequiredArgsConstructor
public class PersistentRestoreEvidenceStore implements RestoreEvidenceStore {
    private final RestoreSessionMapper sessionMapper;
    private final RestoreFileMapper fileMapper;
    private final OrphanObjectMapper orphanMapper;

    @Override
    @Transactional
    public void prepared(PreparedSession prepared) {
        if (prepared == null || blank(prepared.sessionId())) {
            throw new IllegalArgumentException("恢复会话标识不能为空");
        }
        String localKey = idempotencyKey(prepared);
        RestoreSessionEntity session = sessionMapper.findByRemoteSessionId(prepared.sessionId());
        if (session == null) session = sessionMapper.findByLocalIdempotencyKey(localKey);
        boolean remoteSessionChanged = session != null && !prepared.sessionId().equals(session.getRemoteSessionId());
        if (session == null) {
            RestoreSessionEntity created = new RestoreSessionEntity();
            created.setLocalJobId(0L);
            created.setLocalIdempotencyKey(localKey);
            created.setRemoteSessionId(prepared.sessionId());
            created.setSourceOrderId(parseId(prepared.orderId()));
            created.setSnapshotId(0L);
            // conflict_policy 是恢复会话的非空审计字段，必须在首次 insert 前固化。
            created.setConflictPolicy(value(prepared.conflictPolicy(), "REJECT"));
            created.setStatus("PREPARED");
            try {
                sessionMapper.insert(created);
                session = created;
            } catch (DuplicateKeyException raced) {
                session = sessionMapper.findByRemoteSessionId(prepared.sessionId());
                if (session == null) session = sessionMapper.findByLocalIdempotencyKey(localKey);
                if (session == null) throw raced;
            }
        }
        // 线上重试可能返回新的远程会话标识，本地仍沿用同一幂等记录并更新映射。
        session.setRemoteSessionId(prepared.sessionId());
        session.setConflictPolicy(value(prepared.conflictPolicy(), "REJECT"));
        session.setPreparedRevision(prepared.manifestRevision());
        session.setSignedPlanDigest(prepared.signedPlanDigest());
        session.setRelationSetHash(prepared.relationSetHash());
        session.setManifestSchemaVersion(prepared.manifestSchemaVersion());
        session.setBackupCompleteness(value(prepared.backupCompleteness(), "COMPLETE"));
        session.setRelationshipStateHash(prepared.relationshipStateHash());
        if (remoteSessionChanged || (!"COMMITTED".equals(session.getStatus()) && !"ABORTED".equals(session.getStatus()))) {
            session.setStatus("PREPARED");
        }
        sessionMapper.updateById(session);
    }

    @Override
    @Transactional
    public void uploaded(String sessionId, UploadEvidence evidence) {
        saveUpload(sessionId, evidence, "UPLOADED");
    }

    @Override
    @Transactional
    public void uploadFailed(String sessionId, UploadEvidence evidence) {
        saveUpload(sessionId, evidence, "UPLOAD_UNKNOWN");
    }

    private void saveUpload(String sessionId, UploadEvidence evidence, String status) {
        RestoreSessionEntity session = sessionMapper.findByRemoteSessionId(sessionId);
        if (session == null || session.getId() == null) {
            throw new IllegalStateException("恢复会话尚未持久化: " + sessionId);
        }
        if (evidence == null || blank(evidence.assetIdentity())) {
            throw new IllegalArgumentException("恢复资产证据不能为空");
        }
        RestoreFileEntity file = fileMapper.selectOne(Wrappers.<RestoreFileEntity>lambdaQuery()
                .eq(RestoreFileEntity::getRestoreSessionId, session.getId())
                .eq(RestoreFileEntity::getAssetIdentity, evidence.assetIdentity())
                .last("LIMIT 1"));
        if (file == null) {
            file = new RestoreFileEntity();
            file.setRestoreSessionId(session.getId());
            file.setAssetIdentity(evidence.assetIdentity());
            // bk_restore_file 的对象键、状态、容量和摘要都是非空审计字段，必须首次插入前写全。
            applyUploadEvidence(file, evidence, status);
            fileMapper.insert(file);
        }
        applyUploadEvidence(file, evidence, status);
        fileMapper.updateById(file);
    }

    /** 将一次对象上传结果规范化写入恢复文件证据，供首次插入和幂等更新共同复用。 */
    private static void applyUploadEvidence(RestoreFileEntity file, UploadEvidence evidence, String status) {
        file.setTargetObjectKey(value(evidence.objectKey(), "UNKNOWN"));
        file.setUploadStatus(value(status, "UPLOAD_UNKNOWN"));
        file.setEtag(evidence.etag());
        file.setVersionId(evidence.versionId());
        file.setSize(Math.max(evidence.size(), 0L));
        file.setSha256(value(evidence.sha256(), ""));
        file.setNewUrl(evidence.objectKey());
    }

    @Override
    public List<UploadEvidence> uploads(String sessionId) {
        RestoreSessionEntity session = sessionMapper.findByRemoteSessionId(sessionId);
        if (session == null || session.getId() == null) return List.of();
        return fileMapper.selectList(Wrappers.<RestoreFileEntity>lambdaQuery()
                        .eq(RestoreFileEntity::getRestoreSessionId, session.getId())
                        .eq(RestoreFileEntity::getUploadStatus, "UPLOADED")
                        .orderByAsc(RestoreFileEntity::getId))
                .stream()
                .map(file -> new UploadEvidence(file.getAssetIdentity(), file.getTargetObjectKey(),
                        file.getVersionId(), file.getEtag(), file.getSize() == null ? 0L : file.getSize(),
                        file.getSha256()))
                .toList();
    }

    @Override
    @Transactional
    public void status(String sessionId, String status) {
        RestoreSessionEntity session = sessionMapper.findByRemoteSessionId(sessionId);
        if (session == null) return;
        session.setStatus(value(status, "UNKNOWN"));
        sessionMapper.updateById(session);
    }

    @Override
    @Transactional
    public void markOrphans(String sessionId) {
        RestoreSessionEntity session = sessionMapper.findByRemoteSessionId(sessionId);
        if (session == null || session.getId() == null) return;
        List<RestoreFileEntity> files = fileMapper.selectList(Wrappers.<RestoreFileEntity>lambdaQuery()
                .eq(RestoreFileEntity::getRestoreSessionId, session.getId())
                .in(RestoreFileEntity::getUploadStatus, List.of("UPLOADED", "UPLOAD_UNKNOWN")));
        for (RestoreFileEntity file : files) {
            OrphanObjectEntity orphan = orphanMapper.selectOne(Wrappers.<OrphanObjectEntity>lambdaQuery()
                    .eq(OrphanObjectEntity::getRestoreSessionId, session.getId())
                    .eq(OrphanObjectEntity::getObjectKey, value(file.getTargetObjectKey(), file.getAssetIdentity()))
                    .last("LIMIT 1"));
            if (orphan != null) continue;
            orphan = new OrphanObjectEntity();
            orphan.setRestoreSessionId(session.getId());
            orphan.setPlatform("COS");
            orphan.setBucket("UNKNOWN");
            orphan.setObjectKey(value(file.getTargetObjectKey(), file.getAssetIdentity()));
            orphan.setVersionIdentity(value(file.getVersionId(), "UNVERSIONED"));
            orphan.setConfirmationStatus("PENDING");
            orphan.setEarliestCleanupAt(LocalDateTime.now().plusHours(24));
            orphan.setCleanupStatus("BLOCKED");
            orphanMapper.insert(orphan);
        }
    }

    @Override
    public String currentStatus(String sessionId) {
        RestoreSessionEntity session = sessionMapper.findByRemoteSessionId(sessionId);
        return session == null ? null : session.getStatus();
    }

    private static String idempotencyKey(PreparedSession session) {
        return sha256(String.join("\n", value(session.orderId(), ""), value(session.manifestRevision(), ""),
                value(session.signedPlanDigest(), ""), value(session.relationSetHash(), ""),
                String.valueOf(session.manifestSchemaVersion()), value(session.relationshipStateHash(), "")));
    }

    private static Long parseId(String value) {
        try { return Long.parseLong(value); } catch (Exception ignored) { return 0L; }
    }

    private static String value(String value, String fallback) { return blank(value) ? fallback : value; }
    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256不可用", ex);
        }
    }
}
