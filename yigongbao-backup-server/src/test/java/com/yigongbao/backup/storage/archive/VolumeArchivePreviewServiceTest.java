package com.yigongbao.backup.storage.archive;

import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VolumeArchivePreviewServiceTest {
    @Test void formatsOrderCreationPeriodForPhysicalLabel() {
        assertThat(VolumeArchivePreviewService.periodLabel(
                LocalDateTime.of(2026, 1, 1, 10, 0), LocalDateTime.of(2026, 3, 15, 20, 0)))
                .isEqualTo("20260101-20260315");
    }

    @Test void returnsManagedOrderSummaryWithoutBlockingOnAnUnregisteredPhysicalFile() {
        ManagedVolumeArtifactCatalog catalog = mock(ManagedVolumeArtifactCatalog.class);
        VolumeArchiveBlockerService blockers = mock(VolumeArchiveBlockerService.class);
        LocalDateTime from = LocalDateTime.of(2026, 8, 24, 8, 0);
        LocalDateTime to = LocalDateTime.of(2026, 8, 25, 18, 0);
        VolumeArchiveItemEntity orderPackage = new VolumeArchiveItemEntity();
        orderPackage.setArtifactType("ORDER_PACKAGE");
        VolumeArchiveItemEntity supplement = new VolumeArchiveItemEntity();
        supplement.setArtifactType("SUPPLEMENT_FILE");
        ManagedVolumeArtifactCatalog.FrozenManifest manifest = new ManagedVolumeArtifactCatalog.FrozenManifest(
                List.of(orderPackage, supplement), 2, 4096, from, to, "manifest-sha");
        when(catalog.preview(7L)).thenReturn(new ManagedVolumeArtifactCatalog.PreviewInspection(
                manifest, List.of("业务目录存在未登记文件：orders/orphan.zip")));
        when(blockers.inspect(7L, 8L)).thenReturn(List.of());

        VolumeArchivePreviewService.ArchivePreview preview =
                new VolumeArchivePreviewService(catalog, blockers).preview(7L, 8L);

        assertThat(preview.orderCount()).isEqualTo(2);
        assertThat(preview.orderPackageCount()).isEqualTo(1);
        assertThat(preview.supplementFileCount()).isEqualTo(1);
        assertThat(preview.managedFileCount()).isEqualTo(2);
        assertThat(preview.totalBytes()).isEqualTo(4096);
        assertThat(preview.periodLabel()).isEqualTo("20260824-20260825");
        assertThat(preview.blockers()).isEmpty();
    }
}
