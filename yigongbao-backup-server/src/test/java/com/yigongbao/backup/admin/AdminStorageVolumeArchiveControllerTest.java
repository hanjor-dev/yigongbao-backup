package com.yigongbao.backup.admin;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yigongbao.backup.order.OrderSnapshotEntity;
import com.yigongbao.backup.order.OrderSnapshotMapper;
import com.yigongbao.backup.storage.archive.*;
import com.yigongbao.backup.storage.lease.StorageVolumeReadLeaseService;
import com.yigongbao.backup.storage.volume.*;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AdminStorageVolumeArchiveControllerTest {
    @Test void listsArchiveAttemptsIndependentlyWithCurrentVolumeDisplayInformation() {
        VolumeArchiveMapper archiveMapper=mock(VolumeArchiveMapper.class);
        VolumeArchiveEntity older=archive(10L,1L,2L,"SUCCEEDED");
        VolumeArchiveEntity newer=archive(20L,1L,2L,"VERIFY_FAILED");
        when(archiveMapper.selectList(any())).thenReturn(List.of(older,newer));
        StorageVolumeMapper volumeMapper=mock(StorageVolumeMapper.class);
        VolumeArchiveItemMapper itemMapper=mock(VolumeArchiveItemMapper.class);
        when(itemMapper.selectList(any())).thenReturn(List.of(verified(20L),verified(20L),verified(20L)));
        when(volumeMapper.selectBatchIds(any())).thenReturn(List.of(volume(1L,"源盘","COLD","OFFLINE"),volume(2L,"接替盘","HOT","ONLINE")));
        AdminStorageVolumeArchiveController controller=new AdminStorageVolumeArchiveController(
                mock(VolumeArchivePreviewService.class),mock(VolumeArchiveService.class),archiveMapper,
                itemMapper,mock(StorageVolumeReadLeaseService.class),
                mock(StorageVolumeLifecycleService.class),volumeMapper,mock(OrderSnapshotMapper.class));

        try(MockedStatic<StpUtil> auth=mockStatic(StpUtil.class)) {
            List<AdminStorageVolumeArchiveController.ArchiveListItem> result=controller.listArchives().data();
            assertThat(result).extracting(item->item.archive().getId()).containsExactly(20L,10L);
            assertThat(result.getFirst().sourceVolume().displayName()).isEqualTo("源盘");
            assertThat(result.getFirst().sourceVolume().mountStatus()).isEqualTo("OFFLINE");
            assertThat(result.getFirst().successorVolume().displayName()).isEqualTo("接替盘");
            assertThat(result.getFirst().verifiedFileCount()).isEqualTo(3L);
            assertThat(result.get(1).verifiedFileCount()).isZero();
            verify(itemMapper,times(1)).selectList(any());
            verify(itemMapper,never()).selectCount(any());
            auth.verify(()->StpUtil.checkRole("ADMIN"));
        }
    }

    @Test void pagesArchiveItemsAndReturnsBusinessOrderCode() {
        VolumeArchiveService archives=mock(VolumeArchiveService.class);
        VolumeArchiveEntity archive=archive(7L,1L,2L,"SUCCEEDED");
        when(archives.required(7L)).thenReturn(archive);
        VolumeArchiveItemMapper itemMapper=mock(VolumeArchiveItemMapper.class);
        VolumeArchiveItemEntity item=verified(7L); item.setSnapshotId(101L); item.setSourceOrderId(99L);
        Page<VolumeArchiveItemEntity> page=new Page<>(2,50,81); page.setRecords(List.of(item));
        when(itemMapper.selectPage(any(Page.class),any())).thenReturn(page);
        OrderSnapshotMapper snapshotMapper=mock(OrderSnapshotMapper.class);
        OrderSnapshotEntity snapshot=new OrderSnapshotEntity(); snapshot.setId(101L); snapshot.setOrderCode("YGB-2026-0001");
        when(snapshotMapper.selectBatchIds(any())).thenReturn(List.of(snapshot));
        AdminStorageVolumeArchiveController controller=new AdminStorageVolumeArchiveController(
                mock(VolumeArchivePreviewService.class),archives,mock(VolumeArchiveMapper.class),itemMapper,
                mock(StorageVolumeReadLeaseService.class),mock(StorageVolumeLifecycleService.class),
                mock(StorageVolumeMapper.class),snapshotMapper);

        try(MockedStatic<StpUtil> auth=mockStatic(StpUtil.class)) {
            AdminStorageVolumeArchiveController.ArchiveDetail result=controller.detail(7L,2,50).data();
            assertThat(result.items().current()).isEqualTo(2);
            assertThat(result.items().size()).isEqualTo(50);
            assertThat(result.items().total()).isEqualTo(81);
            assertThat(result.items().records()).singleElement().satisfies(value -> {
                assertThat(value.getOrderCode()).isEqualTo("YGB-2026-0001");
                assertThat(value.getSourceOrderId()).isEqualTo(99L);
            });
            verify(itemMapper).selectPage(any(Page.class),any());
            verify(itemMapper,never()).selectList(any());
            auth.verify(()->StpUtil.checkRole("ADMIN"));
        }
    }
    private static VolumeArchiveItemEntity verified(long archiveId){
        VolumeArchiveItemEntity value=new VolumeArchiveItemEntity();value.setArchiveId(archiveId);
        value.setVerificationStatus("VERIFIED");return value;
    }

    private static VolumeArchiveEntity archive(long id,long source,long successor,String phase){
        VolumeArchiveEntity value=new VolumeArchiveEntity();value.setId(id);value.setSourceVolumeId(source);value.setSuccessorVolumeId(successor);value.setPhase(phase);value.setManagedFileCount(4L);return value;
    }
    private static StorageVolumeEntity volume(long id,String name,String tier,String mount){
        StorageVolumeEntity value=new StorageVolumeEntity();value.setId(id);value.setDisplayName(name);value.setVolumeCode("V-"+id);value.setTier(tier);value.setMountStatus(mount);value.setWriteStatus("READ_ONLY");return value;
    }
}
