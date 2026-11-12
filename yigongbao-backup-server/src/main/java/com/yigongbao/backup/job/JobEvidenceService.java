package com.yigongbao.backup.job;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yigongbao.backup.order.OrderSnapshotEntity;
import com.yigongbao.backup.order.OrderSnapshotMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.yigongbao.backup.storage.volume.StorageVolumeEntity;
import com.yigongbao.backup.storage.volume.StorageVolumeMapper;
import java.util.List;
import com.yigongbao.backup.remote.RemoteCallException;

/** 持久化任务的订单级、文件级进度以及可展示的订单业务快照。 */
@Service
public class JobEvidenceService {
    private final JobOrderMapper orders;
    private final JobFileMapper files;
    private final StorageVolumeMapper volumes;
    private final OrderSnapshotMapper snapshots;

    /** 兼容不启动 Spring 容器的单元测试。 */
    public JobEvidenceService(JobOrderMapper orders, JobFileMapper files, StorageVolumeMapper volumes) {
        this(orders, files, volumes, null);
    }

    @Autowired
    public JobEvidenceService(JobOrderMapper orders, JobFileMapper files, StorageVolumeMapper volumes,
                              OrderSnapshotMapper snapshots) {
        this.orders = orders;
        this.files = files;
        this.volumes = volumes;
        this.snapshots = snapshots;
    }

    /** 在任何远程调用前创建本批全部订单，保证总数、待处理数和取消行为可追踪。 */
    public void initializeOrders(long jobId, List<Long> sourceOrderIds) {
        if (sourceOrderIds == null) return;
        sourceOrderIds.stream().filter(java.util.Objects::nonNull).distinct()
                // 任务恢复执行时保留已完成订单的终态，不能将其重置为 PENDING 后重复恢复。
                .filter(orderId -> findOrder(jobId, orderId) == null)
                .forEach(orderId -> order(jobId, orderId, null, "PENDING", null));
    }

    /** 读取任务订单状态，用于执行器租约恢复后重建补偿队列。 */
    public List<JobOrderEntity> orders(long jobId) {
        List<JobOrderEntity> rows = orders.selectList(Wrappers.<JobOrderEntity>lambdaQuery()
                .eq(JobOrderEntity::getJobId, jobId)
                .orderByAsc(JobOrderEntity::getId));
        if (snapshots == null || rows == null || rows.isEmpty()) return rows;
        List<Long> snapshotIds = rows.stream().map(JobOrderEntity::getSnapshotId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        if (snapshotIds.isEmpty()) return rows;
        java.util.Map<Long, OrderSnapshotEntity> byId = snapshots.selectBatchIds(snapshotIds).stream()
                .collect(java.util.stream.Collectors.toMap(OrderSnapshotEntity::getId, item -> item));
        rows.forEach(row -> {
            OrderSnapshotEntity snapshot = byId.get(row.getSnapshotId());
            if (snapshot == null) return;
            row.setBackupCompleteness(snapshot.getBackupCompleteness());
            row.setRelationGapCount(snapshot.getRelationGapCount());
        });
        return rows;
    }

    /** 原子消耗一次订单级重试预算，并返回数据库中的累计次数。 */
    public int consumeRetry(long jobId, long sourceOrderId) {
        if (orders.incrementRetryCount(jobId, sourceOrderId) != 1) {
            throw new IllegalStateException("任务订单不存在，无法记录重试次数: " + sourceOrderId);
        }
        JobOrderEntity row = findOrder(jobId, sourceOrderId);
        if (row == null || row.getRetryCount() == null) {
            throw new IllegalStateException("任务订单重试次数读取失败: " + sourceOrderId);
        }
        return row.getRetryCount();
    }

    public void updateDegradePhase(long jobId, long sourceOrderId, String phase) {
        if (orders.updateDegradePhase(jobId, sourceOrderId, phase) != 1) {
            throw new IllegalStateException("任务订单不存在，无法记录自动降级阶段: " + sourceOrderId);
        }
    }

    /** 执行前置条件失败时，将整批目标标记为失败，使列表统计和失败重试都有完整范围。 */
    public void failOrders(long jobId, List<Long> sourceOrderIds, Throwable failure) {
        if (sourceOrderIds == null) return;
        sourceOrderIds.stream().filter(java.util.Objects::nonNull).distinct()
                .forEach(orderId -> order(jobId, orderId, null, "FAILED", failure));
    }

    public void order(long jobId, Long sourceId, Long snapshotId, String status, Throwable failure) {
        JobOrderEntity row = findOrder(jobId, sourceId);
        if (row == null) {
            row = new JobOrderEntity(); row.setJobId(jobId); row.setSourceOrderId(sourceId);
            row.setSnapshotId(snapshotId); row.setStatus(status); evidence(row, failure); orders.insert(row);
        } else {
            if (snapshotId != null) row.setSnapshotId(snapshotId);
            row.setStatus(status); evidence(row, failure); orders.updateById(row);
        }
    }

    /** 将清单中的完整订单业务信息固化在任务明细，供历史任务独立展示。 */
    public void orderMetadata(long jobId, long sourceId, Long snapshotId, String orderCode,
                              String orderCreatedAt, String sourceStatus, String businessJson) {
        JobOrderEntity row = findOrder(jobId, sourceId);
        if (row == null) { row = new JobOrderEntity(); row.setJobId(jobId); row.setSourceOrderId(sourceId); row.setStatus("RUNNING"); }
        row.setSnapshotId(snapshotId); row.setOrderCode(orderCode); row.setOrderCreatedAt(orderCreatedAt);
        row.setSourceStatus(sourceStatus); row.setOrderBusinessJson(businessJson);
        if (row.getId() == null) orders.insert(row); else orders.updateById(row);
    }

    /** 保存模拟模式的预计文件统计和隔离制品路径。 */
    public void orderSimulationMetadata(long jobId, long sourceId, int expectedFiles, long expectedBytes, String path) {
        JobOrderEntity row = findOrder(jobId, sourceId);
        if (row == null) { row = new JobOrderEntity(); row.setJobId(jobId); row.setSourceOrderId(sourceId); row.setStatus("RUNNING"); }
        row.setExpectedFileCount(expectedFiles); row.setExpectedBytes(expectedBytes);
        if (path != null && !path.isBlank()) row.setSimulationPath(path);
        if (row.getId() == null) orders.insert(row); else orders.updateById(row);
    }

    public void file(long jobId,Long sourceId,String identity,String step,String status,long done,long total,Throwable failure){
        file(jobId, sourceId, identity, step, status, done, total, null, failure);
    }

    /** 固化订单级产物所在卷和卷内位置。 */
    public void orderAtVolume(long jobId, long sourceId, long volumeId, String storageKey) {
        JobOrderEntity row = findOrder(jobId, sourceId);
        if (row == null) { row = new JobOrderEntity(); row.setJobId(jobId); row.setSourceOrderId(sourceId); row.setStatus("RUNNING"); }
        StorageVolumeEntity volume = volumes.selectById(volumeId);
        row.setStorageVolumeId(volumeId); row.setStorageKey(storageKey);
        row.setStorageVolumeName(volume == null ? null : volume.getDisplayName());
        row.setStorageTier(volume == null ? null : volume.getTier());
        if (row.getId() == null) orders.insert(row); else orders.updateById(row);
    }

    /** 写入文件处理证据及不可变的卷定位快照。 */
    public void fileAtVolume(long jobId, Long sourceId, String identity, String step, String status,
                             long done, long total, String objectKey, Long volumeId, String storageKey,
                             Throwable failure) {
        JobFileEntity row = files.selectOne(Wrappers.<JobFileEntity>lambdaQuery().eq(JobFileEntity::getJobId, jobId)
                .eq(JobFileEntity::getAssetIdentity, identity).eq(JobFileEntity::getStep, step).last("limit 1"));
        boolean insert = row == null;
        if (insert) {
            row = new JobFileEntity(); row.setJobId(jobId); row.setSourceOrderId(sourceId);
            row.setAssetIdentity(identity); row.setStep(step);
        }
        row.setStatus(status); row.setBytesDone(done);
        if (total > 0 || row.getBytesTotal() == null) row.setBytesTotal(total);
        if (objectKey != null && !objectKey.isBlank()) row.setObjectKey(objectKey);
        evidence(row, failure);
        if (volumeId != null) {
            StorageVolumeEntity volume = volumes.selectById(volumeId);
            row.setStorageVolumeId(volumeId); row.setStorageKey(storageKey);
            row.setStorageVolumeName(volume == null ? null : volume.getDisplayName());
            row.setStorageTier(volume == null ? null : volume.getTier());
        }
        if (insert) files.insert(row); else files.updateById(row);
    }

    /** 固化恢复任务选定的包副本；任务重试不得重新按当前活动卷推导路径。 */
    public void restoreSource(long jobId, long sourceId, long snapshotId, long packageId,
                              long storageVolumeId, String storageKey, String sha256) {
        JobOrderEntity row = findOrder(jobId, sourceId);
        if (row == null) { row = new JobOrderEntity(); row.setJobId(jobId); row.setSourceOrderId(sourceId); row.setStatus("PENDING"); }
        row.setSnapshotId(snapshotId); row.setSourcePackageId(packageId); row.setSourceStorageVolumeId(storageVolumeId);
        row.setSourceStorageKey(storageKey); row.setSourcePackageSha256(sha256);
        StorageVolumeEntity volume = volumes.selectById(storageVolumeId);
        row.setStorageVolumeId(storageVolumeId); row.setStorageKey(storageKey);
        row.setStorageVolumeName(volume == null ? null : volume.getDisplayName());
        row.setStorageTier(volume == null ? null : volume.getTier());
        if (row.getId() == null) orders.insert(row); else orders.updateById(row);
    }

    /** 保存恢复 ZIP 内资产的上传状态；对象键首次写入后随成功/失败状态更新保留。 */
    public void file(long jobId, Long sourceId, String identity, String step, String status,
                     long done, long total, String objectKey, Throwable failure) {
        JobFileEntity row = files.selectOne(Wrappers.<JobFileEntity>lambdaQuery()
                .eq(JobFileEntity::getJobId, jobId).eq(JobFileEntity::getAssetIdentity, identity)
                .eq(JobFileEntity::getStep, step).last("limit 1"));
        if (row == null) {
            row = new JobFileEntity(); row.setJobId(jobId); row.setSourceOrderId(sourceId);
            row.setAssetIdentity(identity); row.setStep(step); row.setObjectKey(objectKey);
            row.setStatus(status); row.setBytesDone(done); row.setBytesTotal(total); evidence(row, failure);
            files.insert(row);
        } else {
            row.setStatus(status); row.setBytesDone(done);
            // 失败回调可能只能提供已上传字节；保留开始阶段写入的原始文件容量。
            if (total > 0 || row.getBytesTotal() == null) row.setBytesTotal(total);
            if (objectKey != null && !objectKey.isBlank()) row.setObjectKey(objectKey);
            evidence(row, failure); files.updateById(row);
        }
    }

    private JobOrderEntity findOrder(long jobId, Long sourceId) {
        return orders.selectOne(Wrappers.<JobOrderEntity>lambdaQuery().eq(JobOrderEntity::getJobId,jobId)
                .eq(JobOrderEntity::getSourceOrderId,sourceId).last("limit 1"));
    }
    /**
     * 成功重试只更新最终状态，不抹掉上一次失败证据。
     *
     * <p>失败码和失败说明同时承担历史审计用途；查询层会把“终态成功 + 历史失败”投影为
     * “已重试成功”。显式保留字段也避免该语义依赖 MyBatis-Plus 的 null 更新策略。</p>
     */
    private static void evidence(JobOrderEntity row,Throwable e){
        if(e==null)return;
        row.setFailureCode(failureCode(e));row.setFailureMessage(safe(e));
    }
    private static void evidence(JobFileEntity row,Throwable e){
        if(e==null)return;
        row.setFailureCode(failureCode(e));row.setFailureMessage(safe(e));
    }
    private static String failureCode(Throwable e){
        if(e==null)return null;
        if(e instanceof JobFailureException failure)return failure.code();
        if(e instanceof JobPreconditionException precondition)return precondition.code();
        if(e instanceof RemoteCallException remote&&remote.code()!=null&&!remote.code().isBlank())return remote.code();
        return e.getClass().getSimpleName();
    }
    private static String safe(Throwable e){String m=e.getMessage();return m==null?"任务项失败":m.substring(0,Math.min(1000,m.length()));}
}
