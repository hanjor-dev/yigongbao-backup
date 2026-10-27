package com.yigongbao.backup.order;

import com.yigongbao.backup.storage.local.LocalDirectoryArtifactStore;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PersistentSupplementServiceTest {
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
        saved.setStorageKey("orders/order-1/supplements/" + id + "-engine.step");
        saved.setInternalFilename(id + "-engine.step"); saved.setOriginalFilename("engine.step"); saved.setSize(3L);
        when(mapper.selectList(any())).thenReturn(List.of(saved));
        assertThat(service.openForSnapshot(3L, id).readAllBytes()).containsExactly('a', 'b', 'c');
        verify(mapper).insert(any(BkSupplementFileEntity.class));
    }
}
