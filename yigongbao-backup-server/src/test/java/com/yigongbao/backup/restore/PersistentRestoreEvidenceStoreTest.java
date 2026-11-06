package com.yigongbao.backup.restore;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PersistentRestoreEvidenceStoreTest {
    @Test
    void persistsSessionAndUploadEvidenceIdempotently() {
        RestoreSessionMapper sessions = mock(RestoreSessionMapper.class);
        RestoreFileMapper files = mock(RestoreFileMapper.class);
        OrphanObjectMapper orphans = mock(OrphanObjectMapper.class);
        RestoreSessionEntity session = new RestoreSessionEntity();
        session.setId(9L);
        session.setRemoteSessionId("remote-1");
        session.setStatus("PREPARED");

        when(sessions.findByRemoteSessionId("remote-1")).thenReturn(null, session, session, session);
        RestoreFileEntity existing = new RestoreFileEntity();
        existing.setId(11L);
        when(files.selectOne(any())).thenReturn(null, existing);
        AtomicReference<String> insertedObjectKey = new AtomicReference<>();
        AtomicReference<String> insertedStatus = new AtomicReference<>();
        AtomicReference<Long> insertedSize = new AtomicReference<>();
        AtomicReference<String> insertedSha256 = new AtomicReference<>();
        AtomicReference<String> insertedConflictPolicy = new AtomicReference<>();
        doAnswer(invocation -> {
            RestoreSessionEntity value = invocation.getArgument(0);
            insertedConflictPolicy.set(value.getConflictPolicy());
            value.setId(9L);
            return 1;
        }).when(sessions).insert(any(RestoreSessionEntity.class));
        doAnswer(invocation -> {
            RestoreFileEntity value = invocation.getArgument(0);
            insertedObjectKey.set(value.getTargetObjectKey());
            insertedStatus.set(value.getUploadStatus());
            insertedSize.set(value.getSize());
            insertedSha256.set(value.getSha256());
            value.setId(11L);
            return 1;
        }).when(files).insert(any(RestoreFileEntity.class));

        PersistentRestoreEvidenceStore store = new PersistentRestoreEvidenceStore(sessions, files, orphans);
        store.prepared(new RestoreEvidenceStore.PreparedSession("remote-1", "1001", "r1", "digest", "rel", "REJECT"));
        RestoreEvidenceStore.UploadEvidence evidence =
                new RestoreEvidenceStore.UploadEvidence("asset-a", "orders/1001/a", "v1", "etag", 12, "sha");
        store.uploaded("remote-1", evidence);
        store.uploaded("remote-1", evidence);

        verify(sessions).insert(any(RestoreSessionEntity.class));
        verify(files).insert(any(RestoreFileEntity.class));
        verify(files, times(2)).updateById(any(RestoreFileEntity.class));
        assertThat(insertedObjectKey.get()).isEqualTo("orders/1001/a");
        assertThat(insertedStatus.get()).isEqualTo("UPLOADED");
        assertThat(insertedSize.get()).isEqualTo(12L);
        assertThat(insertedSha256.get()).isEqualTo("sha");
        assertThat(insertedConflictPolicy.get()).isEqualTo("REJECT");
        assertThat(session.getStatus()).isEqualTo("PREPARED");
    }

    @Test
    void readsOnlyUploadedEvidenceForCommit() {
        RestoreSessionMapper sessions = mock(RestoreSessionMapper.class);
        RestoreFileMapper files = mock(RestoreFileMapper.class);
        OrphanObjectMapper orphans = mock(OrphanObjectMapper.class);
        RestoreSessionEntity session = new RestoreSessionEntity();
        session.setId(3L);
        when(sessions.findByRemoteSessionId("remote-2")).thenReturn(session);
        RestoreFileEntity file = new RestoreFileEntity();
        file.setAssetIdentity("asset-a");
        file.setTargetObjectKey("orders/1002/a");
        file.setVersionId("v2");
        file.setEtag("etag");
        file.setSize(3L);
        file.setSha256("sha");
        when(files.selectList(any())).thenReturn(List.of(file));

        List<RestoreEvidenceStore.UploadEvidence> result =
                new PersistentRestoreEvidenceStore(sessions, files, orphans).uploads("remote-2");

        assertThat(result).singleElement().satisfies(item -> {
            assertThat(item.assetIdentity()).isEqualTo("asset-a");
            assertThat(item.objectKey()).isEqualTo("orders/1002/a");
            assertThat(item.versionId()).isEqualTo("v2");
        });
    }

    @Test
    void replacesAbortedLocalSessionWithNewRemotePreparedSession() {
        RestoreSessionMapper sessions = mock(RestoreSessionMapper.class);
        RestoreSessionEntity aborted = new RestoreSessionEntity();
        aborted.setId(8L);
        aborted.setRemoteSessionId("remote-old");
        aborted.setStatus("ABORTED");
        when(sessions.findByRemoteSessionId("remote-new")).thenReturn(null);
        when(sessions.findByLocalIdempotencyKey(anyString())).thenReturn(aborted);

        new PersistentRestoreEvidenceStore(sessions, mock(RestoreFileMapper.class), mock(OrphanObjectMapper.class))
                .prepared(new RestoreEvidenceStore.PreparedSession("remote-new", "1001", "r1", "digest", "rel", "REJECT"));

        assertThat(aborted.getRemoteSessionId()).isEqualTo("remote-new");
        assertThat(aborted.getStatus()).isEqualTo("PREPARED");
    }

    @Test
    void recordsUploadedObjectsAsPendingOrphansAfterAbort() {
        RestoreSessionMapper sessions = mock(RestoreSessionMapper.class);
        RestoreFileMapper files = mock(RestoreFileMapper.class);
        OrphanObjectMapper orphans = mock(OrphanObjectMapper.class);
        RestoreSessionEntity session = new RestoreSessionEntity();
        session.setId(4L);
        when(sessions.findByRemoteSessionId("remote-3")).thenReturn(session);
        RestoreFileEntity file = new RestoreFileEntity();
        file.setTargetObjectKey("orders/1003/a");
        file.setAssetIdentity("asset-a");
        file.setVersionId("v3");
        file.setUploadStatus("UPLOADED");
        when(files.selectList(any())).thenReturn(List.of(file));
        when(orphans.selectOne(any())).thenReturn(null);

        new PersistentRestoreEvidenceStore(sessions, files, orphans).markOrphans("remote-3");

        verify(orphans).insert(any(OrphanObjectEntity.class));
    }
}
