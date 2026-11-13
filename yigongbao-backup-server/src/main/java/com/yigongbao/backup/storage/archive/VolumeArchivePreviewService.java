package com.yigongbao.backup.storage.archive;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** 无副作用的封存预览。正式启动会再次检查并冻结清单。 */
@Service
@RequiredArgsConstructor
public class VolumeArchivePreviewService {
    private final ManagedVolumeArtifactCatalog catalog;
    private final VolumeArchiveBlockerService blockers;

    public ArchivePreview preview(long sourceId, Long successorId) {
        ManagedVolumeArtifactCatalog.PreviewInspection inspection = catalog.preview(sourceId);
        ManagedVolumeArtifactCatalog.FrozenManifest manifest = inspection.manifest();
        long packages = manifest.items().stream().filter(v -> "ORDER_PACKAGE".equals(v.getArtifactType())).count();
        long supplements = manifest.items().stream().filter(v -> "SUPPLEMENT_FILE".equals(v.getArtifactType())).count();
        List<VolumeArchiveBlockerService.Blocker> found = new ArrayList<>(blockers.inspect(sourceId, successorId));
        return new ArchivePreview(manifest.orderCount(), packages, supplements, manifest.items().size(), manifest.totalBytes(),
                manifest.orderCreatedFrom(), manifest.orderCreatedTo(),
                periodLabel(manifest.orderCreatedFrom(), manifest.orderCreatedTo()), List.copyOf(found));
    }

    public static String periodLabel(LocalDateTime from, LocalDateTime to) {
        if (from == null || to == null) return null;
        DateTimeFormatter value = DateTimeFormatter.ofPattern("yyyyMMdd");
        return value.format(from) + "-" + value.format(to);
    }

    public record ArchivePreview(long orderCount, long orderPackageCount, long supplementFileCount,
                                 long managedFileCount, long totalBytes,
                                 LocalDateTime orderCreatedFrom, LocalDateTime orderCreatedTo, String periodLabel,
                                 List<VolumeArchiveBlockerService.Blocker> blockers) { }
}
