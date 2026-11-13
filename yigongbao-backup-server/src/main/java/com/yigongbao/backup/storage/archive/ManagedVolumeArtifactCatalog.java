package com.yigongbao.backup.storage.archive;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yigongbao.backup.order.*;
import com.yigongbao.backup.storage.location.ArtifactCopyEntity;
import com.yigongbao.backup.storage.location.ArtifactCopyMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

/** 从副本台账生成可重复、可审计的整盘受管文件清单。 */
@Service
@RequiredArgsConstructor
public class ManagedVolumeArtifactCatalog {
    private final ArtifactCopyMapper copyMapper;
    private final OrderPackageMapper packageMapper;
    private final BkSupplementFileMapper supplementMapper;
    private final OrderSnapshotMapper snapshotMapper;
    private final VolumeArchiveItemMapper itemMapper;

    @Transactional
    public FrozenManifest freeze(long archiveId, long volumeId) {
        FrozenManifest manifest = inspect(archiveId, volumeId);
        persist(manifest);
        return manifest;
    }

    public void persist(FrozenManifest manifest) {
        for (VolumeArchiveItemEntity item : manifest.items()) itemMapper.insert(item);
    }

    /** 构造与 freeze 完全相同的清单，但不落库，供预览使用。 */
    public FrozenManifest inspect(long archiveId, long volumeId) {
        return inspectInternal(archiveId, volumeId).manifest();
    }

    /** 预览只统计已登记受管文件；未登记物理文件由后台巡检清理，不阻塞封存。 */
    public PreviewInspection preview(long volumeId) {
        return inspectInternal(0, volumeId);
    }

    private PreviewInspection inspectInternal(long archiveId, long volumeId) {
        List<ArtifactCopyEntity> allActiveCopies = copyMapper.selectList(Wrappers.<ArtifactCopyEntity>lambdaQuery()
                .eq(ArtifactCopyEntity::getStorageVolumeId, volumeId)
                .eq(ArtifactCopyEntity::getLifecycle, "ACTIVE")
                .eq(ArtifactCopyEntity::getIsDeleted, 0));
        List<ArtifactCopyEntity> invalid = allActiveCopies.stream()
                .filter(copy -> !"VERIFIED".equals(copy.getState()) && !"PRESENT".equals(copy.getState())).toList();
        if (!invalid.isEmpty()) throw new IllegalStateException("存储卷存在不可封存副本状态："
                + invalid.getFirst().getState() + "，路径=" + invalid.getFirst().getStorageKey());
        List<ArtifactCopyEntity> copies = allActiveCopies.stream().sorted(Comparator
                .comparing(ArtifactCopyEntity::getStorageKey, Comparator.nullsFirst(String::compareTo))
                .thenComparing(ArtifactCopyEntity::getId, Comparator.nullsLast(Long::compareTo))).toList();
        Map<String, VolumeArchiveItemEntity> unique = new LinkedHashMap<>();
        for (ArtifactCopyEntity copy : copies) {
            String key = normalizeStorageKey(copy.getStorageKey());
            VolumeArchiveItemEntity item = toItem(archiveId, volumeId, key, copy);
            VolumeArchiveItemEntity previous = unique.putIfAbsent(key, item);
            if (previous != null && (!Objects.equals(previous.getExpectedSize(), item.getExpectedSize())
                    || !Objects.equals(previous.getExpectedSha256(), item.getExpectedSha256()))) {
                throw new IllegalStateException("同一路径存在冲突的副本台账：" + key);
            }
        }
        if (unique.isEmpty()) throw new IllegalStateException("存储卷没有可封存的受管文件");
        List<VolumeArchiveItemEntity> items = new ArrayList<>(unique.values());
        Set<Long> orders = new HashSet<>();
        long bytes = 0;
        LocalDateTime from = null, to = null;
        StringBuilder canonical = new StringBuilder();
        for (VolumeArchiveItemEntity item : items) {
            if (item.getSourceOrderId() == null || item.getOrderCreatedAt() == null) {
                throw new IllegalStateException("受管文件缺少订单创建时间：" + item.getStorageKey());
            }
            orders.add(item.getSourceOrderId());
            bytes += item.getExpectedSize() == null ? 0 : item.getExpectedSize();
            from = from == null || item.getOrderCreatedAt().isBefore(from) ? item.getOrderCreatedAt() : from;
            to = to == null || item.getOrderCreatedAt().isAfter(to) ? item.getOrderCreatedAt() : to;
            canonical.append(item.getStorageKey()).append('|').append(item.getExpectedSize()).append('|')
                    .append(item.getExpectedSha256()).append('\n');
        }
        FrozenManifest manifest = new FrozenManifest(List.copyOf(items), orders.size(), bytes, from, to,
                hex(sha256(canonical.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        return new PreviewInspection(manifest, List.of());
    }

    /** 提交 COLD 前再次核对已登记受管副本，未登记物理文件不参与清单也不阻塞封存。 */
    public void validateFrozen(long archiveId, long volumeId, String expectedManifestSha256,
                               List<VolumeArchiveItemEntity> items) {
        long invalid = copyMapper.selectCount(Wrappers.<ArtifactCopyEntity>lambdaQuery()
                .eq(ArtifactCopyEntity::getStorageVolumeId, volumeId).eq(ArtifactCopyEntity::getLifecycle, "ACTIVE")
                .notIn(ArtifactCopyEntity::getState, "VERIFIED", "PRESENT").eq(ArtifactCopyEntity::getIsDeleted, 0));
        if (invalid > 0) throw new IllegalStateException("冻结后出现异常副本状态");
        FrozenManifest current = inspect(archiveId, volumeId);
        if (!Objects.equals(expectedManifestSha256, current.sha256())) {
            throw new IllegalStateException("冻结后受管副本清单已变化，拒绝提交 COLD");
        }
    }

    private VolumeArchiveItemEntity toItem(long archiveId, long volumeId, String key, ArtifactCopyEntity copy) {
        String artifactType = normalizeArtifactType(copy.getArtifactType());
        VolumeArchiveItemEntity item = new VolumeArchiveItemEntity();
        item.setArchiveId(archiveId); item.setStorageVolumeId(volumeId); item.setStorageKey(key);
        item.setArtifactType(artifactType); item.setArtifactId(copy.getArtifactId());
        item.setExpectedSize(copy.getSize()); item.setExpectedSha256(copy.getSha256());
        item.setVerificationStatus("PENDING");
        Long snapshotId;
        if ("ORDER_PACKAGE".equals(artifactType)) {
            OrderPackageEntity value = packageMapper.selectById(copy.getArtifactId());
            if (value == null) throw new IllegalStateException("订单包台账不存在：" + copy.getArtifactId());
            snapshotId = value.getSnapshotId();
            item.setExpectedSize(value.getPackageSize()); item.setExpectedSha256(value.getPackageSha256());
            item.setExpectedManifestSha256(value.getManifestSha256());
        } else if ("SUPPLEMENT_FILE".equals(artifactType)) {
            BkSupplementFileEntity value = supplementMapper.selectById(copy.getArtifactId());
            if (value == null) throw new IllegalStateException("补传文件台账不存在：" + copy.getArtifactId());
            snapshotId = value.getSnapshotId(); item.setExpectedSize(value.getSize()); item.setExpectedSha256(value.getSha256());
        } else throw new IllegalStateException("不支持的受管制品类型：" + copy.getArtifactType());
        OrderSnapshotEntity snapshot = snapshotMapper.selectById(snapshotId);
        if (snapshot == null) throw new IllegalStateException("订单快照不存在：" + snapshotId);
        item.setSnapshotId(snapshotId); item.setSourceOrderId(snapshot.getSourceOrderId());
        item.setOrderCreatedAt(snapshot.getOrderCreatedAt());
        if (item.getExpectedSize() == null || item.getExpectedSha256() == null || item.getExpectedSha256().isBlank())
            throw new IllegalStateException("受管文件缺少大小或 SHA-256：" + key);
        return item;
    }

    static String normalizeArtifactType(String artifactType) {
        return "SUPPLEMENT".equals(artifactType) ? "SUPPLEMENT_FILE" : artifactType;
    }

    static String normalizeStorageKey(String value) {
        if (value == null || value.isBlank()) throw new IllegalStateException("卷内路径不能为空");
        String normalized = value.replace('\\', '/');
        Path path = Path.of(normalized).normalize();
        if (path.isAbsolute() || normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*")
                || path.startsWith("..")) throw new IllegalStateException("非法卷内路径：" + value);
        return path.toString().replace('\\', '/');
    }

    private static byte[] sha256(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static String hex(byte[] bytes) { return java.util.HexFormat.of().formatHex(bytes); }

    public record FrozenManifest(List<VolumeArchiveItemEntity> items, long orderCount, long totalBytes,
                                 LocalDateTime orderCreatedFrom, LocalDateTime orderCreatedTo, String sha256) { }

    public record PreviewInspection(FrozenManifest manifest, List<String> blockers) { }
}
