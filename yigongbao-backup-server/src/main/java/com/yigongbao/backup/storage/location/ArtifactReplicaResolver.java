package com.yigongbao.backup.storage.location;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yigongbao.backup.storage.volume.StorageVolumeEntity;
import com.yigongbao.backup.storage.volume.StorageVolumeMapper;
import com.yigongbao.backup.storage.volume.StorageVolumeUnavailableException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/** 按副本生命周期和卷可用性选择一个可读副本。 */
@Service
@RequiredArgsConstructor
public class ArtifactReplicaResolver {
    private final ArtifactCopyMapper copyMapper;
    private final StorageVolumeMapper volumeMapper;

    /**
     * 选择当前可读副本。若已存在副本但所属卷均离线，必须返回卷不可用而非文件不存在。
     */
    public ReadableArtifactReplica resolveReadable(String artifactType, long artifactId) {
        List<ArtifactCopyEntity> copies = copyMapper.selectList(Wrappers.<ArtifactCopyEntity>lambdaQuery()
                .eq(ArtifactCopyEntity::getArtifactType, artifactType)
                .eq(ArtifactCopyEntity::getArtifactId, artifactId)
                .eq(ArtifactCopyEntity::getLifecycle, "ACTIVE")
                .eq(ArtifactCopyEntity::getIsDeleted, 0));
        if (copies.isEmpty()) {
            throw new IllegalStateException("未找到可用文件副本");
        }
        return copies.stream()
                .map(copy -> new Candidate(copy, volumeMapper.selectById(copy.getStorageVolumeId())))
                .filter(candidate -> candidate.volume != null && Integer.valueOf(0).equals(candidate.volume.getIsDeleted()))
                .filter(candidate -> "ONLINE".equals(candidate.volume.getMountStatus()))
                .sorted(Comparator.comparing((Candidate candidate) -> tierRank(candidate.volume.getTier()))
                        .thenComparing(candidate -> candidate.copy.getLastVerifiedAt(), Comparator.nullsLast(Comparator.reverseOrder())))
                .map(candidate -> new ReadableArtifactReplica(candidate.copy.getId(), candidate.copy.getArtifactType(),
                        candidate.copy.getArtifactId(), candidate.copy.getStorageVolumeId(), candidate.copy.getStorageKey(),
                        candidate.copy.getSha256(), candidate.volume.getTier()))
                .findFirst().orElseThrow(() -> new StorageVolumeUnavailableException("STORAGE_VOLUME_OFFLINE：文件副本存在但所属存储卷不可访问"));
    }

    private static int tierRank(String tier) {
        return "HOT".equals(tier) ? 0 : "COLD".equals(tier) ? 1 : 2;
    }

    /** 已选择且被任务快照固化的可读副本。 */
    public record ReadableArtifactReplica(Long copyId, String artifactType, Long artifactId, Long storageVolumeId,
                                          String storageKey, String sha256, String tier) {
    }

    private record Candidate(ArtifactCopyEntity copy, StorageVolumeEntity volume) {
    }
}
