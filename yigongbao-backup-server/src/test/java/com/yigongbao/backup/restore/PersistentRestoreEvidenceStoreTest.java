package com.yigongbao.backup.restore;

import org.junit.jupiter.api.Test;

import java.util.List;

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
        doAnswer(invocation -> {
            RestoreSessionEntity value = invocation.getArgument(0);
            value.setId(9L);
            return 1;
        }).when(sessions).insert(any(RestoreSessionEntity.class));
        doAnswer(invocation -> {
            RestoreFileEntity value = invocation.getArgument(0);
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
