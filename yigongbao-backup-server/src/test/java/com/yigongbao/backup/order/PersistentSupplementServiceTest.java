package com.yigongbao.backup.order;

import com.yigongbao.backup.storage.local.LocalDirectoryArtifactStore;
import com.yigongbao.backup.storage.api.VolumeArtifactStore;
import com.yigongbao.backup.storage.location.ArtifactCopyEntity;
import com.yigongbao.backup.storage.location.ArtifactCopyMapper;
import com.yigongbao.backup.storage.location.ArtifactReplicaResolver;
import com.yigongbao.backup.storage.volume.StorageVolumeMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import java.lang.reflect.Modifier;

class PersistentSupplementServiceTest {
    @Test
    void productionServiceCanBeTransactionProxied() {
        assertThat(Modifier.isFinal(SupplementService.class.getModifiers())).isFalse();
    }

    @Test
    void productionUploadRegistersReplicaAndDownloadUsesReadableReplica() throws Exception {
        var root = Files.createTempDirectory("persistent-supp-volume");
        VolumeArtifactStore store = mock(VolumeArtifactStore.class);
        BkSupplementFileMapper mapper = mock(BkSupplementFileMapper.class);
        ArtifactCopyMapper copies = mock(ArtifactCopyMapper.class);
        ArtifactReplicaResolver resolver = mock(ArtifactReplicaResolver.class);
        doAnswer(invocation -> {
            String key = invocation.getArgument(1);
            var input = (java.io.InputStream) invocation.getArgument(2);
            var path = root.resolve(key);
            Files.createDirectories(path.getParent());
            Files.write(path, input.readAllBytes());
            return path;
        }).when(store).put(eq(7L), anyString(), any());
        when(store.resolve(eq(7L), anyString())).thenAnswer(invocation -> root.resolve((String) invocation.getArgument(1)));
        doAnswer(invocation -> { ((BkSupplementFileEntity) invocation.getArgument(0)).setId(11L); return 1; })
                .when(mapper).insert(any(BkSupplementFileEntity.class));
        StorageVolumeMapper volumes = mock(StorageVolumeMapper.class);
        SupplementService service = new SupplementService(store, mapper, null, volumes, copies, resolver);

        String fileId = service.uploadForSnapshot(3L, "order-1", "engine.step",
                new ByteArrayInputStream("abc".getBytes()), 3, 9L, "application/octet-stream", null, 7L);
        verify(copies).insert(argThat((ArtifactCopyEntity copy) -> "SUPPLEMENT_FILE".equals(copy.getArtifactType())
                && Long.valueOf(11L).equals(copy.getArtifactId()) && Long.valueOf(7L).equals(copy.getStorageVolumeId())));

        BkSupplementFileEntity saved = new BkSupplementFileEntity();
        saved.setId(11L); saved.setSnapshotId(3L); saved.setInternalFilename(fileId + "-engine.step");
        saved.setStorageVolumeId(7L); saved.setStorageKey("old-key"); saved.setSize(3L);
        when(mapper.selectList(any())).thenReturn(List.of(saved));
        when(resolver.resolveReadable("SUPPLEMENT_FILE", 11L)).thenReturn(
                new ArtifactReplicaResolver.ReadableArtifactReplica(1L, "SUPPLEMENT_FILE", 11L, 8L, "replica-key", null, "COLD"));
        var replicaVolume = new com.yigongbao.backup.storage.volume.StorageVolumeEntity();
        replicaVolume.setId(8L); replicaVolume.setDisplayName("冷备副本"); replicaVolume.setTier("COLD"); replicaVolume.setMountStatus("ONLINE");
        when(volumes.selectById(8L)).thenReturn(replicaVolume);
        when(store.open(8L, "replica-key")).thenReturn(new ByteArrayInputStream("abc".getBytes()));
        assertThat(service.listForSnapshot(3L, "order-1").getFirst())
                .extracting(SupplementService.SupplementInfo::storageVolumeId,
                        SupplementService.SupplementInfo::storageKey, SupplementService.SupplementInfo::storageVolumeName)
                .containsExactly(8L, "replica-key", "冷备副本");
        assertThat(service.openForSnapshot(3L, fileId).readAllBytes()).containsExactly('a', 'b', 'c');
    }

    @Test
    void uploadWritesFileAndMetadataAndCanOpenByFileId() throws Exception {
        var root = Files.createTempDirectory("persistent-supp");
        BkSupplementFileMapper mapper = mock(BkSupplementFileMapper.class);
        doAnswer(invocation -> { BkSupplementFileEntity entity = invocation.getArgument(0); entity.setId(11L); return 1; })
                .when(mapper).insert(any(BkSupplementFileEntity.class));
        SupplementService service = new SupplementService(new LocalDirectoryArtifactStore(root), Set.of("step"), 100, mapper);

        String id = service.uploadForSnapshot(3L, "order-1", "engine.step",
                new ByteArrayInputStream("abc".getBytes()), 3, 9L, "application/octet-stream", "remark");
        BkSupplementFileEntity saved = new BkSupplementFileEntity(); saved.setSnapshotId(3L);
        saved.setStorageKey("supplements/order-1/" + id + "-engine.step");
        saved.setInternalFilename(id + "-engine.step"); saved.setOriginalFilename("engine.step"); saved.setSize(3L);
        when(mapper.selectList(any())).thenReturn(List.of(saved));
        assertThat(service.openForSnapshot(3L, id).readAllBytes()).containsExactly('a', 'b', 'c');
        verify(mapper).insert(any(BkSupplementFileEntity.class));
    }
}
