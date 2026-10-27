package com.yigongbao.backup.backup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yigongbao.backup.order.OrderSnapshotEntity;
import com.yigongbao.backup.order.OrderSnapshotMapper;
import com.yigongbao.backup.job.PersistentJobService;
import com.yigongbao.backup.job.JobIdempotency;
import com.yigongbao.backup.job.PersistentJobCoordinator;
import com.yigongbao.backup.storage.api.ArtifactStore;
import com.yigongbao.backup.storage.api.StorageProbe;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.List;

/** 按配置周期发现线上候选并逐订单执行热备；任一订单失败不影响其他订单。 */
@Service
@Slf4j
@ConditionalOnProperty(prefix = "backup.online", name = "enabled", havingValue = "true")
public class ScheduledBackupService {
    private final OnlineCandidateClient candidates;
    private final OnlineManifestClient manifests;
    private final CosObjectClient cos;
    private final ArtifactStore store;
    private final ObjectMapper mapper;
    private final OrderSnapshotMapper snapshots;
    private final StorageProbe storageProbe;
    private final PersistentJobService jobs;
    private final PersistentJobCoordinator coordinator;

    public ScheduledBackupService(OnlineCandidateClient candidates, OnlineManifestClient manifests,
                                  CosObjectClient cos, ArtifactStore store, ObjectMapper mapper,
                                  OrderSnapshotMapper snapshots, StorageProbe storageProbe,
                                  PersistentJobService jobs, PersistentJobCoordinator coordinator) {
        this.candidates = candidates; this.manifests = manifests; this.cos = cos;
        this.store = store; this.mapper = mapper; this.snapshots = snapshots; this.storageProbe = storageProbe; this.jobs = jobs; this.coordinator = coordinator;
    }

    /** 定时入口；线上适配器未启用时由配置条件保证不会实例化。 */
    @Scheduled(fixedDelayString = "${backup.schedule.interval-ms:1800000}")
    public synchronized void runScheduled() {
        String cursor = "";
        int processed = 0;
        do {
            OnlineCandidateClient.Page page = candidates.search(cursor, 100);
            List<OnlineCandidateClient.Candidate> eligible = page.items().stream()
                    .filter(candidate -> candidate.orderId() != null)
                    .filter(candidate -> !isTerminal(candidate.eventStatus()))
                    .filter(candidate -> !alreadyBackedUp(candidate.orderId()))
                    .toList();
            if (eligible.isEmpty()) { cursor = page.nextCursor(); if (!page.hasMore()) break; continue; }
            List<Long> orderIds = eligible.stream().map(OnlineCandidateClient.Candidate::orderId).distinct().sorted().toList();
            String payload;
            try { payload = mapper.writeValueAsString(new ManualBackupJobHandler.Payload(orderIds)); }
            catch (Exception exception) { throw new IllegalStateException("定时备份任务参数序列化失败", exception); }
            coordinator.create("MANUAL_BACKUP", JobIdempotency.key("MANUAL_BACKUP", 0L, payload), 0L, payload);
            processed += orderIds.size();
            cursor = page.nextCursor();
            if (!page.hasMore()) break;
        } while (cursor != null && !cursor.isBlank());
        if (processed > 0) log.info("定时热备完成: successCount={}", processed);
    }

    /** 每日执行一次候选漏单对账；对账失败不影响下一轮增量备份。 */
    @Scheduled(cron = "${backup.schedule.reconcile-cron:0 20 2 * * *}")
    public void reconcileCandidates() {
        try {
            OnlineCandidateClient.ReconcileResult result = candidates.reconcile(null, null);
            log.info("线上候选对账完成: scanned={}, repaired={}, cancelled={}, deleted={}",
                    result.scanned(), result.repaired(), result.cancelled(), result.deleted());
        } catch (RuntimeException error) {
            log.error("线上候选对账失败，将在下一周期重试", error);
        }
    }

    /** 在下载前以清单总大小做保守预检，容量未知或不足时不启动大任务。 */
    private void ensureSpace(BackupManifest manifest) {
        long required = manifest.assets().stream().mapToLong(BackupManifest.Asset::size).reduce(0L, (a, b) -> {
            if (b < 0 || Long.MAX_VALUE - a < b) throw new IllegalStateException("订单清单容量溢出");
            return a + b;
        });
        StorageProbe.StorageSnapshot snapshot = storageProbe.probe(store.root());
        if (!snapshot.capacityKnown() || snapshot.usableBytes() < required) {
            throw new IllegalStateException("热备空间不足或容量未知");
        }
    }

    private boolean alreadyBackedUp(Long orderId) {
        return snapshots.selectCount(Wrappers.<OrderSnapshotEntity>lambdaQuery()
                .eq(OrderSnapshotEntity::getSourceOrderId, orderId)
                .in(OrderSnapshotEntity::getSnapshotStatus, "AVAILABLE", "SUCCESS", "BACKED_UP")) > 0;
    }

    private void saveSnapshot(OnlineCandidateClient.Candidate candidate, BackupManifest manifest) {
        Map<String, Object> order = manifest.order() == null ? Map.of() : manifest.order();
        OrderSnapshotEntity entity = snapshots.selectOne(Wrappers.<OrderSnapshotEntity>lambdaQuery()
                .eq(OrderSnapshotEntity::getSourceOrderId, candidate.orderId()).last("limit 1"));
        if (entity == null) entity = new OrderSnapshotEntity();
        entity.setSourceOrderId(candidate.orderId());
        entity.setOrderCode(firstText(order, "orderCode", "orderNo", "code", "id"));
        entity.setOrderCreatedAt(candidate.orderCreatedAt() == null ? null : candidate.orderCreatedAt().toLocalDateTime());
        entity.setSourceStatus(firstText(order, "status", "statusCode"));
        entity.setClassicCase(Boolean.TRUE.equals(order.get("classicCase")));
        entity.setOrderRawJson(write(order));
        entity.setDisplayJson(write(order));
        entity.setRelatedBusinessJson(write(manifest.assets()));
        entity.setManifestRevision(manifest.manifestRevision());
        entity.setManifestSha256(manifest.manifestSha256());
        entity.setSnapshotStatus("AVAILABLE");
        entity.setBackupCompletedAt(LocalDateTime.now());
        entity.setLatestVerifiedAt(LocalDateTime.now());
        entity.setCloudRollup("PRESENT"); entity.setHotRollup("PRESENT"); entity.setColdRollup("ABSENT");
        if (entity.getId() == null) snapshots.insert(entity); else snapshots.updateById(entity);
    }

    private String write(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException("订单快照序列化失败", e); } }
    private static String firstText(Map<String, Object> map, String... keys) { for (String key : keys) { Object value = map.get(key); if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value); } return null; }
    private static boolean isTerminal(String status) { return status != null && ("CANCELLED".equalsIgnoreCase(status) || "DELETED".equalsIgnoreCase(status) || "DELETE".equalsIgnoreCase(status)); }
}
