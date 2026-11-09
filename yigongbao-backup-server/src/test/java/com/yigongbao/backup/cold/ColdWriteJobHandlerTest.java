package com.yigongbao.backup.cold;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yigongbao.backup.job.JobEntity;
import com.yigongbao.backup.job.JobExecutionContext;
import com.yigongbao.backup.order.ColdSupplementCoverageService;
import com.yigongbao.backup.order.OrderPackageMapper;
import com.yigongbao.backup.storage.api.SpaceReservationService;
import com.yigongbao.backup.storage.api.VolumeArtifactStore;
import com.yigongbao.backup.storage.location.ArtifactCopyMapper;
import com.yigongbao.backup.storage.location.ArtifactReplicaResolver;
import com.yigongbao.backup.storage.volume.StorageVolumeEntity;
import com.yigongbao.backup.storage.volume.StorageVolumeMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** 冷备写入只允许已纳管且在线的可写 COLD 卷。 */
class ColdWriteJobHandlerTest {
    @Test
    void rejectsOfflineColdTargetBeforeWritingAnyArtifact() throws Exception {
        StorageVolumeMapper volumes = mock(StorageVolumeMapper.class);
        StorageVolumeEntity offline = new StorageVolumeEntity();
        offline.setId(9L); offline.setTier("COLD"); offline.setMountStatus("OFFLINE"); offline.setWriteStatus("STANDBY");
        when(volumes.selectById(9L)).thenReturn(offline);
        ColdBatchMapper batches = mock(ColdBatchMapper.class);
        ColdBatchEntity batch = new ColdBatchEntity(); batch.setId(3L); batch.setStatus("CREATED");
        when(batches.selectById(3L)).thenReturn(batch);
        OrderPackageMapper packages = mock(OrderPackageMapper.class);
        ColdWriteJobHandler handler = new ColdWriteJobHandler(batches, mock(ColdBatchArtifactMapper.class),
                mock(ColdBatchWorkflowService.class), mock(ColdSupplementCoverageService.class), new ObjectMapper(),
                mock(SpaceReservationService.class), volumes, packages, mock(ArtifactCopyMapper.class),
                mock(ArtifactReplicaResolver.class), mock(VolumeArtifactStore.class));
        JobEntity job = new JobEntity(); job.setConfigSnapshotJson(new ObjectMapper().writeValueAsString(
                new ColdWriteJobHandler.Payload(3L, 9L, java.util.List.of("orders/A/A.zip"), "digest")));

        assertThatThrownBy(() -> handler.execute(mock(JobExecutionContext.class), job))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("目标冷备卷不可写入");
        verifyNoInteractions(packages);
    }
}
