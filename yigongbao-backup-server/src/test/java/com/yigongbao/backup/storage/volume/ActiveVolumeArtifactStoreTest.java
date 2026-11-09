package com.yigongbao.backup.storage.volume;

import com.yigongbao.backup.storage.api.VolumeArtifactStore;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ActiveVolumeArtifactStoreTest {
    @Test
    void resolvesActiveVolumeForEachLegacyOperation() throws Exception {
        ActiveVolumeSelectionService selection = mock(ActiveVolumeSelectionService.class);
        StorageVolumeEntity active = new StorageVolumeEntity(); active.setId(7L); active.setRootPath("D:/volume-seven");
        when(selection.requireActiveHotVolume()).thenReturn(active);
        VolumeArtifactStore volumes = mock(VolumeArtifactStore.class);
        Path expected = Path.of("D:/volume-seven/orders/a.zip");
        when(volumes.put(eq(7L), eq("orders/a.zip"), any())).thenReturn(expected);

        ActiveVolumeArtifactStore store = new ActiveVolumeArtifactStore(selection, volumes);
        assertThat(store.put("orders/a.zip", new ByteArrayInputStream(new byte[] {1}))).isEqualTo(expected);
        verify(volumes).put(eq(7L), eq("orders/a.zip"), any());
    }
}
