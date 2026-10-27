package com.yigongbao.backup.backup;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yigongbao.backup.job.JobEntity;
import com.yigongbao.backup.job.JobExecutionContext;
import com.yigongbao.backup.job.JobHandler;
import com.yigongbao.backup.order.OrderSnapshotEntity;
import com.yigongbao.backup.order.OrderSnapshotMapper;
import com.yigongbao.backup.storage.api.ArtifactStore;
import com.yigongbao.backup.storage.api.SpacePlanner.SpaceReservation;
import com.yigongbao.backup.storage.api.SpaceReservationService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 按管理员显式选择的订单在后台执行整单热备。 */
@Component
@RequiredArgsConstructor
public class ManualBackupJobHandler implements JobHandler {
    private final ObjectProvider<OnlineManifestClient> manifests;
    private final ObjectProvider<CosObjectClient> cosClients;
    private final ArtifactStore store;
    private final ObjectMapper mapper;
    private final OrderSnapshotMapper snapshots;
    private final SpaceReservationService reservations;

    @Override
    public void execute(JobExecutionContext context, JobEntity job) throws Exception {
        Payload payload = mapper.readValue(job.getConfigSnapshotJson(), Payload.class);
        OnlineManifestClient manifestsClient = manifests.getIfAvailable();
        CosObjectClient cos = cosClients.getIfAvailable();
        if (manifestsClient == null || cos == null) throw new IllegalStateException("线上热备客户端未配置");
        long done = 0;
        for (Long orderId : payload.sourceOrderIds()) {
            context.safePoint("BACKUP_ORDER_" + orderId, done);
            BackupManifest manifest = manifestsClient.fetch(String.valueOf(orderId));
            long estimatedBytes = estimate(manifest);
            SpaceReservation reservation = reservations.reserve("hot", estimatedBytes, job.getId());
            context.orderStarted(orderId, orderId);
            try {
                if (manifest.assets() != null) {
                    for (BackupManifest.Asset asset : manifest.assets()) {
                        context.fileStarted(orderId, asset.assetId(), "BACKUP_DOWNLOAD", asset.size());
                    }
                }
                new OrderBackupPipeline(manifestsClient, cos, store, mapper)
                        .backup(String.valueOf(orderId), manifest);
                if (manifest.assets() != null) {
                    for (BackupManifest.Asset asset : manifest.assets()) {
                        context.fileSucceeded(orderId, asset.assetId(), "BACKUP_DOWNLOAD", asset.size());
                    }
                }
                save(orderId, manifest);
                context.orderSucceeded();
            } finally {
                reservations.release(reservation);
            }
            done++;
            context.safePoint("BACKUP_ORDER_COMPLETED", done);
        }
    }

    private static long estimate(BackupManifest manifest) {
        long total = 0L;
        if (manifest == null || manifest.assets() == null) throw new IllegalStateException("订单清单不存在");
        for (BackupManifest.Asset asset : manifest.assets()) {
            if (asset.size() < 0 || Long.MAX_VALUE - total < asset.size()) {
                throw new IllegalStateException("订单清单容量溢出");
            }
            total += asset.size();
        }
        return Math.max(1L, total);
    }

    private void save(Long orderId, BackupManifest manifest) {
        Map<String, Object> order = manifest.order() == null ? Map.of() : manifest.order();
        OrderSnapshotEntity entity = snapshots.selectOne(Wrappers.<OrderSnapshotEntity>lambdaQuery()
                .eq(OrderSnapshotEntity::getSourceOrderId, orderId).last("limit 1"));
        if (entity == null) entity = new OrderSnapshotEntity();
        entity.setSourceOrderId(orderId);
        entity.setOrderCode(text(order, "orderCode", "orderNo", "code", "id"));
        entity.setSourceStatus(text(order, "status", "statusCode"));
        entity.setOrderRawJson(json(order));
        entity.setDisplayJson(json(order));
        entity.setRelatedBusinessJson(json(manifest.assets()));
        entity.setManifestRevision(manifest.manifestRevision());
        entity.setManifestSha256(manifest.manifestSha256());
        entity.setSnapshotStatus("AVAILABLE");
        entity.setBackupCompletedAt(LocalDateTime.now());
        entity.setLatestVerifiedAt(LocalDateTime.now());
        entity.setCloudRollup("PRESENT");
        entity.setHotRollup("PRESENT");
        entity.setColdRollup("ABSENT");
        if (entity.getId() == null) snapshots.insert(entity); else snapshots.updateById(entity);
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("快照序列化失败", exception);
        }
    }

    private static String text(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value);
        }
        return null;
    }

    public record Payload(List<Long> sourceOrderIds) {
    }
}
