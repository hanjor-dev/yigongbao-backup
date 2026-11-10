package com.yigongbao.backup.job;

import com.yigongbao.backup.storage.volume.StorageVolumeEntity;
import com.yigongbao.backup.storage.volume.StorageVolumeMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class JobEvidenceServiceTest {
    @Test void initializesEveryScheduledOrderBeforeRemoteWorkStarts() {
        JobOrderMapper orders = mock(JobOrderMapper.class);
        when(orders.selectOne(any())).thenReturn(null);
        JobEvidenceService service = new JobEvidenceService(orders, mock(JobFileMapper.class), mock(StorageVolumeMapper.class));

        service.initializeOrders(99L, List.of(1L, 2L, 3L, 4L, 5L));

        verify(orders, times(5)).insert(argThat((JobOrderEntity row) -> "PENDING".equals(row.getStatus())));
    }

    @Test void storesOrderBusinessSnapshotOnTaskDetail() {
        JobOrderMapper orders = mock(JobOrderMapper.class);
        JobOrderEntity row = new JobOrderEntity(); row.setId(1L); row.setJobId(99L); row.setSourceOrderId(5L);
        when(orders.selectOne(any())).thenReturn(row);
        JobEvidenceService service = new JobEvidenceService(orders, mock(JobFileMapper.class), mock(StorageVolumeMapper.class));

        service.orderMetadata(99L, 5L, 88L, "20260702000001", "2026-07-02T13:14:13", "1040", "{\"orgName\":\"市场部\"}");

        verify(orders).updateById(argThat((JobOrderEntity saved) -> saved.getSnapshotId().equals(88L)
                && "20260702000001".equals(saved.getOrderCode())
                && saved.getOrderBusinessJson().contains("市场部")));
    }

    @Test void storesOrderPackageLocationWithImmutableVolumeSnapshot() {
        JobOrderMapper orders = mock(JobOrderMapper.class);
        StorageVolumeMapper volumes = mock(StorageVolumeMapper.class);
        JobOrderEntity row = new JobOrderEntity(); row.setId(1L); row.setJobId(99L); row.setSourceOrderId(5L);
        StorageVolumeEntity volume = new StorageVolumeEntity();
        volume.setId(7L); volume.setDisplayName("热备盘A"); volume.setTier("HOT");
        when(orders.selectOne(any())).thenReturn(row);
        when(volumes.selectById(7L)).thenReturn(volume);
        JobEvidenceService service = new JobEvidenceService(orders, mock(JobFileMapper.class), volumes);

        service.orderAtVolume(99L, 5L, 7L, "orders/20261109/O-5.zip");

        verify(orders).updateById(argThat((JobOrderEntity saved) -> Long.valueOf(7L).equals(saved.getStorageVolumeId())
                && "orders/20261109/O-5.zip".equals(saved.getStorageKey())
                && "热备盘A".equals(saved.getStorageVolumeName()) && "HOT".equals(saved.getStorageTier())));
    }

    @Test void storesFileLocationWithImmutableVolumeSnapshot() {
        JobFileMapper files = mock(JobFileMapper.class);
        StorageVolumeMapper volumes = mock(StorageVolumeMapper.class);
        JobFileEntity row = new JobFileEntity(); row.setId(2L); row.setJobId(99L);
        row.setAssetIdentity("asset-1"); row.setStep("BACKUP_DOWNLOAD");
        StorageVolumeEntity volume = new StorageVolumeEntity();
        volume.setId(7L); volume.setDisplayName("热备盘A"); volume.setTier("HOT");
        when(files.selectOne(any())).thenReturn(row);
        when(volumes.selectById(7L)).thenReturn(volume);
        JobEvidenceService service = new JobEvidenceService(mock(JobOrderMapper.class), files, volumes);

        service.fileAtVolume(99L, 5L, "asset-1", "BACKUP_DOWNLOAD", "SUCCEEDED",
                12L, 12L, null, 7L, "orders/O-5.zip!/files/asset-1.dcm", null);

        verify(files, times(1)).updateById(argThat((JobFileEntity saved) -> Long.valueOf(7L).equals(saved.getStorageVolumeId())
                && "orders/O-5.zip!/files/asset-1.dcm".equals(saved.getStorageKey())
                && "热备盘A".equals(saved.getStorageVolumeName()) && "HOT".equals(saved.getStorageTier())));
    }
}
