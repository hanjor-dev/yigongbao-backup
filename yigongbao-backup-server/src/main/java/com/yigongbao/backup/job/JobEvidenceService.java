package com.yigongbao.backup.job;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;

/** 持久化任务的订单级、文件级进度以及可展示的订单业务快照。 */
@Service
@RequiredArgsConstructor
public class JobEvidenceService {
    private final JobOrderMapper orders;
    private final JobFileMapper files;

    /** 在任何远程调用前创建本批全部订单，保证总数、待处理数和取消行为可追踪。 */
    public void initializeOrders(long jobId, List<Long> sourceOrderIds) {
        if (sourceOrderIds == null) return;
        sourceOrderIds.stream().filter(java.util.Objects::nonNull).distinct()
                // 任务恢复执行时保留已完成订单的终态，不能将其重置为 PENDING 后重复恢复。
                .filter(orderId -> findOrder(jobId, orderId) == null)
                .forEach(orderId -> order(jobId, orderId, null, "PENDING", null));
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

    /** 写入文件处理证据及不可变的卷定位快照。 */
    public void fileAtVolume(long jobId, Long sourceId, String identity, String step, String status,
                             long done, long total, String objectKey, Long volumeId, String storageKey,
                             String volumeName, String tier, Throwable failure) {
        file(jobId, sourceId, identity, step, status, done, total, objectKey, failure);
        JobFileEntity row = files.selectOne(Wrappers.<JobFileEntity>lambdaQuery().eq(JobFileEntity::getJobId, jobId)
                .eq(JobFileEntity::getAssetIdentity, identity).eq(JobFileEntity::getStep, step).last("limit 1"));
        if (row != null && volumeId != null) {
            row.setStorageVolumeId(volumeId); row.setStorageKey(storageKey); row.setStorageVolumeName(volumeName); row.setStorageTier(tier);
            files.updateById(row);
        }
    }

    /** 固化恢复任务选定的包副本；任务重试不得重新按当前活动卷推导路径。 */
    public void restoreSource(long jobId, long sourceId, long snapshotId, long packageId,
                              long storageVolumeId, String storageKey, String sha256) {
        JobOrderEntity row = findOrder(jobId, sourceId);
        if (row == null) { row = new JobOrderEntity(); row.setJobId(jobId); row.setSourceOrderId(sourceId); row.setStatus("PENDING"); }
        row.setSnapshotId(snapshotId); row.setSourcePackageId(packageId); row.setSourceStorageVolumeId(storageVolumeId);
        row.setSourceStorageKey(storageKey); row.setSourcePackageSha256(sha256);
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
    private static void evidence(JobOrderEntity row,Throwable e){row.setFailureCode(e==null?null:e.getClass().getSimpleName());row.setFailureMessage(e==null?null:safe(e));}
    private static void evidence(JobFileEntity row,Throwable e){row.setFailureCode(e==null?null:e.getClass().getSimpleName());row.setFailureMessage(e==null?null:safe(e));}
    private static String safe(Throwable e){String m=e.getMessage();return m==null?"任务项失败":m.substring(0,Math.min(1000,m.length()));}
}
