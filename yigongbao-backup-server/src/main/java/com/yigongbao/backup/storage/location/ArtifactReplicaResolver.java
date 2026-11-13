package com.yigongbao.backup.storage.location;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yigongbao.backup.storage.archive.VolumeArchiveEntity;
import com.yigongbao.backup.storage.archive.VolumeArchiveMapper;
import com.yigongbao.backup.storage.volume.StorageVolumeAccessPolicy;
import com.yigongbao.backup.storage.volume.StorageVolumeEntity;
import com.yigongbao.backup.storage.volume.StorageVolumeMapper;
import com.yigongbao.backup.storage.volume.StorageVolumeUnavailableException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.time.LocalDateTime;

/** 按副本生命周期和卷可用性选择一个可读副本。 */
@Service
@RequiredArgsConstructor
public class ArtifactReplicaResolver {
    private final ArtifactCopyMapper copyMapper;
    private final StorageVolumeMapper volumeMapper;
    private final VolumeArchiveMapper archiveMapper;
    private final StorageVolumeAccessPolicy accessPolicy;

    /**
     * 选择当前可读副本。若已存在副本但所属卷均离线，必须返回卷不可用而非文件不存在。
     */
    public ReadableArtifactReplica resolveReadable(String artifactType, long artifactId) {
        ArtifactStorageProjection projection = project(artifactType, List.of(artifactId)).get(artifactId);
        if (projection == null) {
            throw new IllegalStateException("未找到可用文件副本");
        }
        if (!projection.readable()) {
            throw new StorageVolumeUnavailableException(projection.unavailableReason());
        }
        return new ReadableArtifactReplica(projection.copyId(), artifactType, artifactId,
                projection.storageVolumeId(), projection.storageKey(), projection.sha256(), projection.storageTier());
    }

    /**
     * 批量投影制品副本的当前卷状态。副本位置和任务快照保持不变，HOT/COLD 与挂载状态始终来自卷台账。
     */
    public Map<Long, ArtifactStorageProjection> project(String artifactType, Collection<Long> artifactIds) {
        if (artifactIds == null || artifactIds.isEmpty()) return Map.of();
        List<Long> ids = artifactIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return Map.of();
        List<ArtifactCopyEntity> copies = copyMapper.selectList(Wrappers.<ArtifactCopyEntity>lambdaQuery()
                .eq(ArtifactCopyEntity::getArtifactType, artifactType)
                .in(ArtifactCopyEntity::getArtifactId, ids)
                .eq(ArtifactCopyEntity::getLifecycle, "ACTIVE")
                .eq(ArtifactCopyEntity::getIsDeleted, 0));
        if (copies == null || copies.isEmpty()) return Map.of();
        List<Long> volumeIds = copies.stream().map(ArtifactCopyEntity::getStorageVolumeId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, StorageVolumeEntity> volumeById = new HashMap<>();
        if (!volumeIds.isEmpty()) {
            List<StorageVolumeEntity> volumeRows = volumeMapper.selectBatchIds(volumeIds);
            if (volumeRows != null) volumeRows.forEach(volume -> volumeById.put(volume.getId(), volume));
        }
        List<Long> archiveIds = volumeById.values().stream()
                .filter(volume -> "ARCHIVE_GUARD".equals(volume.getOperationStatus()))
                .map(StorageVolumeEntity::getOperationRefId).filter(Objects::nonNull).distinct().toList();
        Map<Long, StorageVolumeAccessPolicy.ArchiveGuardContext> guardByArchiveId = new HashMap<>();
        if (!archiveIds.isEmpty()) {
            List<VolumeArchiveEntity> archives = archiveMapper.selectBatchIds(archiveIds);
            if (archives != null) archives.forEach(archive -> guardByArchiveId.put(archive.getId(),
                    new StorageVolumeAccessPolicy.ArchiveGuardContext(archive.getId(), archive.getSuccessorVolumeId(),
                            archive.getPhase(), !Integer.valueOf(1).equals(archive.getIsDeleted()))));
        }
        Map<Long, List<ArtifactCopyEntity>> byArtifact = new LinkedHashMap<>();
        copies.forEach(copy -> byArtifact.computeIfAbsent(copy.getArtifactId(), ignored -> new java.util.ArrayList<>()).add(copy));
        Map<Long, ArtifactStorageProjection> result = new LinkedHashMap<>();
        byArtifact.forEach((artifactId, artifactCopies) -> result.put(artifactId,
                projection(artifactCopies, volumeById, guardByArchiveId)));
        return result;
    }

    private ArtifactStorageProjection projection(List<ArtifactCopyEntity> copies,
                                                 Map<Long, StorageVolumeEntity> volumeById,
                                                 Map<Long, StorageVolumeAccessPolicy.ArchiveGuardContext> guardByArchiveId) {
        List<Candidate> candidates = copies.stream().map(copy -> new Candidate(copy, volumeById.get(copy.getStorageVolumeId())))
                .filter(candidate -> candidate.volume != null && !Integer.valueOf(1).equals(candidate.volume.getIsDeleted()))
                .toList();
        List<Long> missingVolumeIds = copies.stream()
                .filter(copy -> copy.getStorageVolumeId() != null)
                .filter(copy -> volumeById.get(copy.getStorageVolumeId()) == null
                        || Integer.valueOf(1).equals(volumeById.get(copy.getStorageVolumeId()).getIsDeleted()))
                .map(ArtifactCopyEntity::getStorageVolumeId).distinct().toList();
        String diagnosticReason = missingVolumeIds.isEmpty() ? null
                : "STORAGE_VOLUME_MISSING：活动副本引用的存储卷台账不存在或已删除，卷ID="
                + missingVolumeIds.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining("、"));
        String hotRollup = tierRollup(candidates, "HOT");
        String coldRollup = tierRollup(candidates, "COLD");
        boolean hotPresent = !"ABSENT".equals(hotRollup);
        boolean coldPresent = !"ABSENT".equals(coldRollup);
        Candidate selected = candidates.stream().sorted(Comparator
                        .comparing((Candidate candidate) -> !readable(candidate, guardByArchiveId))
                        .thenComparing(candidate -> !healthyCopy(candidate.copy))
                        .thenComparing(candidate -> tierRank(candidate.volume.getTier()))
                        .thenComparing(candidate -> candidate.copy.getLastVerifiedAt(), Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(candidate -> candidate.copy.getId(), Comparator.nullsLast(Comparator.naturalOrder())))
                .findFirst().orElse(null);
        if (selected == null) {
            ArtifactCopyEntity copy = copies.getFirst();
            return new ArtifactStorageProjection(copy.getId(), copy.getStorageVolumeId(), copy.getStorageKey(), copy.getSha256(),
                    null, null, "UNAVAILABLE", null, null, null, null, null, null,
                    null, null, null,
                    hotPresent, coldPresent, hotRollup, coldRollup, false,
                    "STORAGE_VOLUME_MISSING：存储卷台账不存在", diagnosticReason);
        }
        StorageVolumeEntity volume = selected.volume;
        boolean readable = readable(selected, guardByArchiveId);
        String currentArchiveNote = "COLD".equals(volume.getTier()) && "OFFLINE".equals(volume.getMountStatus())
                ? volume.getArchiveNote() : null;
        return new ArtifactStorageProjection(selected.copy.getId(), volume.getId(), selected.copy.getStorageKey(), selected.copy.getSha256(),
                volume.getDisplayName(), volume.getTier(), volume.getMountStatus(), volume.getWriteStatus(),
                volume.getAdminStatus(), volume.getOperationStatus(), currentArchiveNote, volume.getOfflineLabelNote(),
                volume.getPhysicalLocation(),
                volume.getOrderCreatedFrom(), volume.getOrderCreatedTo(), volume.periodLabel(),
                hotPresent, coldPresent, hotRollup, coldRollup, readable,
                readable ? null : unavailableReason(selected, guardByArchiveId), diagnosticReason);
    }

    private static String tierRollup(List<Candidate> candidates, String tier) {
        boolean healthy = candidates.stream().anyMatch(candidate -> tier.equals(candidate.volume.getTier())
                && healthyCopy(candidate.copy));
        boolean abnormal = candidates.stream().anyMatch(candidate -> tier.equals(candidate.volume.getTier())
                && !healthyCopy(candidate.copy));
        if (healthy && abnormal) return "PARTIAL";
        if (healthy) return "PRESENT";
        return abnormal ? "ERROR" : "ABSENT";
    }

    private static boolean healthyCopy(ArtifactCopyEntity copy) {
        return copy != null && ("VERIFIED".equals(copy.getState()) || "PRESENT".equals(copy.getState()));
    }

    private boolean readable(Candidate candidate,
                             Map<Long, StorageVolumeAccessPolicy.ArchiveGuardContext> guardByArchiveId) {
        return candidate != null && healthyCopy(candidate.copy)
                && accessPolicy.readable(candidate.volume, guard(candidate.volume, guardByArchiveId)).allowed();
    }

    private String unavailableReason(Candidate candidate,
                                     Map<Long, StorageVolumeAccessPolicy.ArchiveGuardContext> guardByArchiveId) {
        if (!healthyCopy(candidate.copy)) {
            return "ARTIFACT_COPY_" + (candidate.copy.getState() == null ? "INVALID" : candidate.copy.getState())
                    + "：文件副本状态异常，不能读取";
        }
        StorageVolumeEntity volume = candidate.volume;
        if (volume == null) return "STORAGE_VOLUME_MISSING：存储卷台账不存在";
        StorageVolumeAccessPolicy.AccessDecision decision = accessPolicy.readable(
                volume, guard(volume, guardByArchiveId));
        return decision.code() + "：" + decision.message();
    }

    private static StorageVolumeAccessPolicy.ArchiveGuardContext guard(
            StorageVolumeEntity volume,
            Map<Long, StorageVolumeAccessPolicy.ArchiveGuardContext> guardByArchiveId) {
        return volume == null || volume.getOperationRefId() == null
                ? null : guardByArchiveId.get(volume.getOperationRefId());
    }

    private static int tierRank(String tier) {
        return "HOT".equals(tier) ? 0 : "COLD".equals(tier) ? 1 : 2;
    }

    /** 已选择且被任务快照固化的可读副本。 */
    public record ReadableArtifactReplica(Long copyId, String artifactType, Long artifactId, Long storageVolumeId,
                                          String storageKey, String sha256, String tier) {
    }

    /** 当前存储状态投影；历史任务字段不在此处改写。 */
    public record ArtifactStorageProjection(Long copyId, Long storageVolumeId, String storageKey, String sha256,
                                            String storageVolumeName, String storageTier, String mountStatus,
                                            String writeStatus, String adminStatus, String operationStatus,
                                            String archiveNote, String offlineLabelNote, String physicalLocation,
                                            LocalDateTime orderCreatedFrom, LocalDateTime orderCreatedTo, String periodLabel,
                                            boolean hotPresent, boolean coldPresent, String hotRollup, String coldRollup,
                                            boolean readable,
                                            String unavailableReason, String diagnosticReason) { }

    private record Candidate(ArtifactCopyEntity copy, StorageVolumeEntity volume) {
    }
}
