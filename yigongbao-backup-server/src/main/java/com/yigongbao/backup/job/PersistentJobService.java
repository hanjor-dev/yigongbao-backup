package com.yigongbao.backup.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yigongbao.backup.order.OrderPackageEntity;
import com.yigongbao.backup.order.OrderPackageMapper;
import com.yigongbao.backup.storage.volume.StorageVolumeMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 任务中心数据库实现；领取使用版本、租约和 fencing token 条件更新。 */
@Service
public class PersistentJobService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final JobMapper mapper;
    private final JobOrderMapper orderMapper;
    private final JobFileMapper fileMapper;
    private final OrderPackageMapper packageMapper;
    private final StorageVolumeMapper volumeMapper;

    /** 调度批次统计值，避免将远端订单明细在创建任务前重复拉取。 */
    public record JobInputBatch(int orderTotal, int fileTotal) {}

    public PersistentJobService(JobMapper mapper) { this(mapper, null, null, null, null); }

    public PersistentJobService(JobMapper mapper, JobOrderMapper orderMapper, JobFileMapper fileMapper) {
        this(mapper, orderMapper, fileMapper, null, null);
    }

    public PersistentJobService(JobMapper mapper, JobOrderMapper orderMapper, JobFileMapper fileMapper,
                                OrderPackageMapper packageMapper) {
        this(mapper, orderMapper, fileMapper, packageMapper, null);
    }

    @Autowired
    public PersistentJobService(JobMapper mapper, JobOrderMapper orderMapper, JobFileMapper fileMapper,
                                OrderPackageMapper packageMapper, StorageVolumeMapper volumeMapper) {
        this.mapper = mapper; this.orderMapper = orderMapper; this.fileMapper = fileMapper;
        this.packageMapper = packageMapper; this.volumeMapper = volumeMapper;
    }

    public Job create(int orderTotal, int fileTotal, long actor) {
        return create(orderTotal, fileTotal, actor, List.of(), List.of());
    }

    /** 创建任务并在同一事务中固化订单、文件明细。 */
    @Transactional
    public Job create(int orderTotal, int fileTotal, long actor, List<OrderInput> orders, List<FileInput> files) {
        if (orderTotal < 0 || fileTotal < 0) throw new IllegalArgumentException("任务统计值不能为负");
        orders = orders == null ? List.of() : List.copyOf(orders);
        files = files == null ? List.of() : List.copyOf(files);
        if ((!orders.isEmpty() && orderMapper == null) || (!files.isEmpty() && fileMapper == null)) {
            throw new IllegalStateException("任务明细持久化未配置");
        }
        int finalOrderTotal = orders.isEmpty() ? orderTotal : orders.size();
        int finalFileTotal = files.isEmpty() ? fileTotal : files.size();
        JobEntity entity = new JobEntity();
        entity.setJobNo(UUID.randomUUID().toString());
        entity.setJobType("MANUAL");
        entity.setStatus(JobState.CREATED.name());
        entity.setIdempotencyKey(UUID.randomUUID().toString());
        entity.setPriority(0);
        entity.setProgressCurrent(0L);
        entity.setProgressTotal((long) finalFileTotal);
        entity.setRequestedBy(actor);
        entity.setRequestedAt(LocalDateTime.now());
        entity.setFencingToken(0L);
        entity.setRetryCount(0);
        entity.setCancelRequested(false);
        entity.setConfigSnapshotJson("{\"orderTotal\":" + finalOrderTotal + ",\"fileTotal\":" + finalFileTotal + "}");
        mapper.insert(entity);
        if (!orders.isEmpty()) {
            for (OrderInput input : orders) {
                JobOrderEntity detail = new JobOrderEntity();
                detail.setJobId(entity.getId()); detail.setSourceOrderId(input.sourceOrderId());
                detail.setSnapshotId(input.snapshotId()); detail.setStatus("PENDING");
                detail.setCreateBy(actor); detail.setUpdateBy(actor); orderMapper.insert(detail);
            }
        }
        if (!files.isEmpty()) {
            for (FileInput input : files) {
                JobFileEntity detail = new JobFileEntity();
                detail.setJobId(entity.getId()); detail.setSourceOrderId(input.sourceOrderId());
                detail.setAssetIdentity(input.assetIdentity()); detail.setStep(input.step()); detail.setStatus("PENDING");
                detail.setBytesDone(0L); detail.setBytesTotal(input.bytesTotal() == null ? 0L : input.bytesTotal());
                detail.setPlatform(input.platform()); detail.setBucket(input.bucket()); detail.setObjectKey(input.objectKey());
                detail.setVersionIdentity(input.versionIdentity()); detail.setTempPath(input.tempPath());
                detail.setCreateBy(actor); detail.setUpdateBy(actor); fileMapper.insert(detail);
            }
        }
        return view(entity, finalOrderTotal, finalFileTotal);
    }

    public List<Job> list() {
        return mapper.selectList(Wrappers.<JobEntity>lambdaQuery().orderByDesc(JobEntity::getId))
                .stream().map(entity -> view(entity, orderTotal(entity), fileTotal(entity))).toList();
    }

    /** 只返回订单备份任务，供“订单备份”页使用。 */
    public List<Job> listBackupJobs() {
        return mapper.selectList(Wrappers.<JobEntity>lambdaQuery()
                        .in(JobEntity::getJobType, "ORDER_BACKUP", "MANUAL_BACKUP")
                        .orderByDesc(JobEntity::getId))
                .stream().map(entity -> viewWithEvidence(entity)).toList();
    }

    /** 服务端分页读取备份任务，避免任务历史增长后一次加载全部明细。 */
    public JobPage listBackupJobs(long current, long size) {
        if (current < 1 || size < 1 || size > 100) throw new IllegalArgumentException("任务分页参数非法");
        Page<JobEntity> page = mapper.selectPage(Page.of(current, size), Wrappers.<JobEntity>lambdaQuery()
                .in(JobEntity::getJobType, "ORDER_BACKUP", "MANUAL_BACKUP").orderByDesc(JobEntity::getId));
        return new JobPage(page.getRecords().stream().map(this::viewWithEvidence).toList(),
                page.getTotal(), page.getCurrent(), page.getSize(), page.getPages());
    }

    /** 只返回线上恢复任务，避免恢复记录与订单备份调度批次混在同一管理列表。 */
    public List<Job> listRestoreJobs() {
        return mapper.selectList(Wrappers.<JobEntity>lambdaQuery()
                        .eq(JobEntity::getJobType, "RESTORE")
                        .orderByDesc(JobEntity::getId))
                .stream().map(this::viewWithEvidence).toList();
    }

    /** 服务端分页读取线上恢复任务；恢复历史可独立长期保留和审计。 */
    public JobPage listRestoreJobs(long current, long size) {
        if (current < 1 || size < 1 || size > 100) throw new IllegalArgumentException("任务分页参数非法");
        Page<JobEntity> page = mapper.selectPage(Page.of(current, size), Wrappers.<JobEntity>lambdaQuery()
                .eq(JobEntity::getJobType, "RESTORE").orderByDesc(JobEntity::getId));
        return new JobPage(page.getRecords().stream().map(this::viewWithEvidence).toList(),
                page.getTotal(), page.getCurrent(), page.getSize(), page.getPages());
    }

    public record JobPage(List<Job> records, long total, long current, long size, long pages) { }

    public Job get(long id) {
        JobEntity entity = required(id);
        return viewWithEvidence(entity);
    }

    /** 查询单个恢复任务，并拒绝通过恢复接口跨类型读取其他运维任务。 */
    public Job getRestoreJob(long id) {
        JobEntity entity = required(id);
        requireRestore(entity);
        return viewWithEvidence(entity);
    }

    /** 查询订单备份任务，并拒绝通过备份日志接口读取其他任务。 */
    public Job getBackupJob(long id) {
        JobEntity entity = required(id);
        if (!"ORDER_BACKUP".equals(entity.getJobType()) && !"MANUAL_BACKUP".equals(entity.getJobType())) {
            throw new IllegalArgumentException("不是订单备份任务");
        }
        return viewWithEvidence(entity);
    }

    /** 分页读取恢复订单明细，保证详情接口也具有任务类型边界。 */
    public JobOrderPage listRestoreOrders(long jobId, long current, long size) {
        requireRestore(required(jobId));
        return listOrders(jobId, current, size);
    }

    /** 读取恢复任务全部订单证据，仅用于在服务端生成“失败订单补偿任务”。 */
    public List<JobOrderEntity> listAllRestoreOrders(long jobId) {
        requireRestore(required(jobId));
        return listOrders(jobId);
    }

    /** 读取恢复 ZIP 上传步骤明细，保证详情接口也具有任务类型边界。 */
    public List<JobFileEntity> listRestoreFiles(long jobId) {
        requireRestore(required(jobId));
        return listFiles(jobId);
    }

    /**
     * 分页读取一个恢复订单 ZIP 内的逐文件上传证据。
     *
     * <p>仅返回 {@code RESTORE_OBJECT_UPLOAD}，不混入订单 ZIP 聚合步骤，避免前端将压缩包误显示为业务文件。</p>
     */
    public JobFilePage listRestoreOrderFiles(long jobId, long sourceOrderId, long current, long size) {
        requireRestore(required(jobId));
        if (sourceOrderId < 1 || current < 1 || size < 1 || size > 100) {
            throw new IllegalArgumentException("恢复文件明细分页参数非法");
        }
        if (fileMapper == null) throw new IllegalStateException("任务文件明细持久化未配置");
        Page<JobFileEntity> page = fileMapper.selectPage(Page.of(current, size),
                Wrappers.<JobFileEntity>lambdaQuery().eq(JobFileEntity::getJobId, jobId)
                        .eq(JobFileEntity::getSourceOrderId, sourceOrderId)
                        .eq(JobFileEntity::getStep, "RESTORE_OBJECT_UPLOAD")
                        .orderByAsc(JobFileEntity::getId));
        return new JobFilePage(page.getRecords(), page.getTotal(), page.getCurrent(), page.getSize(), page.getPages());
    }

    /** 统一的文件级证据分页返回结构。 */
    public record JobFilePage(List<JobFileEntity> records, long total, long current, long size, long pages) { }

    /** 受控内部查询任务参数，仅供需要校验任务归属的业务接口使用。 */
    public String configJson(long id) { return required(id).getConfigSnapshotJson(); }

    public Job claim(long id, String owner, Duration lease) {
        if (owner == null || owner.isBlank() || lease == null || lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("任务租约参数非法");
        }
        JobEntity entity = required(id);
        LocalDateTime now = LocalDateTime.now();
        boolean available = JobState.CREATED.name().equals(entity.getStatus())
                || ((JobState.RUNNING.name().equals(entity.getStatus()) || JobState.RETRYING_FAILED_ORDERS.name().equals(entity.getStatus())) && entity.getLeaseUntil() != null
                && !entity.getLeaseUntil().isAfter(now));
        if (!available) throw new IllegalStateException("任务不可领取");
        UpdateWrapper<JobEntity> update = new UpdateWrapper<>();
        update.eq("id", id).eq("version", entity.getVersion())
                .and(w -> w.eq("status", JobState.CREATED.name())
                        .or().in("status", JobState.RUNNING.name(), JobState.RETRYING_FAILED_ORDERS.name()).le("lease_until", now))
                .set("status", JobState.RUNNING.name())
                .set("lease_owner", owner)
                .set("lease_until", now.plusSeconds(lease.toSeconds()))
                .set("heartbeat_at", now)
                .set("fencing_token", entity.getFencingToken() + 1);
        int rows = mapper.update(null, update);
        if (rows != 1) throw new IllegalStateException("任务已被其他执行器领取");
        return get(id);
    }

    /** 将调度批次置为运行中；调度器重启后可依据该状态恢复或告警。 */
    @Transactional public void markRunning(long id, String owner) {
        JobEntity entity = required(id); LocalDateTime now = LocalDateTime.now();
        entity.setStatus(JobState.RUNNING.name()); entity.setLeaseOwner(owner); entity.setHeartbeatAt(now);
        entity.setStartedAt(now); entity.setLeaseUntil(now.plusHours(2)); entity.setFencingToken((entity.getFencingToken() == null ? 0 : entity.getFencingToken()) + 1);
        if (mapper.updateById(entity) != 1) throw new IllegalStateException("任务状态更新失败");
    }

    /** 固化调度批次最终状态和完成进度。 */
    @Transactional public void finish(long id, boolean success, int completed, String failureMessage) {
        JobEntity entity = required(id); entity.setStatus(success ? JobState.SUCCEEDED.name() : JobState.FAILED.name());
        entity.setProgressCurrent((long) Math.max(0, completed)); entity.setFailureMessage(failureMessage);
        entity.setFinishedAt(LocalDateTime.now()); entity.setLeaseUntil(null);
        if (mapper.updateById(entity) != 1) throw new IllegalStateException("任务完成状态更新失败");
    }

    public List<JobOrderEntity> listOrders(long jobId) {
        if (orderMapper == null) throw new IllegalStateException("任务订单明细持久化未配置");
        List<JobOrderEntity> rows = orderMapper.selectList(Wrappers.<JobOrderEntity>lambdaQuery().eq(JobOrderEntity::getJobId, jobId)
                .orderByAsc(JobOrderEntity::getId));
        return enrichPackageBytes(rows);
    }

    /** 分页读取任务订单明细，并回填已生成订单 ZIP 的实际容量。 */
    public JobOrderPage listOrders(long jobId, long current, long size) {
        if (orderMapper == null) throw new IllegalStateException("任务订单明细持久化未配置");
        if (current < 1 || size < 1 || size > 100) throw new IllegalArgumentException("订单明细分页参数非法");
        Page<JobOrderEntity> page = orderMapper.selectPage(Page.of(current, size),
                Wrappers.<JobOrderEntity>lambdaQuery().eq(JobOrderEntity::getJobId, jobId)
                        .orderByAsc(JobOrderEntity::getId));
        return new JobOrderPage(enrichPackageBytes(page.getRecords()), page.getTotal(), page.getCurrent(),
                page.getSize(), page.getPages());
    }

    public record JobOrderPage(List<JobOrderEntity> records, long total, long current, long size, long pages) { }

    public List<JobFileEntity> listFiles(long jobId) {
        if (fileMapper == null) throw new IllegalStateException("任务文件明细持久化未配置");
        return fileMapper.selectList(Wrappers.<JobFileEntity>lambdaQuery().eq(JobFileEntity::getJobId, jobId)
                .orderByAsc(JobFileEntity::getId));
    }

    private List<JobOrderEntity> enrichPackageBytes(List<JobOrderEntity> rows) {
        if (rows == null || rows.isEmpty()) return rows;
          // 历史名称/层级是审计快照；当前状态写入独立展示字段，禁止覆盖历史证据。
        if (volumeMapper != null) rows.forEach(row -> {
            if (row.getStorageVolumeId() == null) return;
            var volume = volumeMapper.selectById(row.getStorageVolumeId());
              if (volume != null) { row.setCurrentStorageTier(volume.getTier()); row.setCurrentStorageVolumeName(volume.getDisplayName()); row.setCurrentMountStatus(volume.getMountStatus()); row.setCurrentOperationStatus(volume.getOperationStatus()); }
        });
        if (packageMapper == null) return rows;
        List<Long> snapshotIds = rows.stream().map(JobOrderEntity::getSnapshotId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        if (snapshotIds.isEmpty()) return rows;
        Map<Long, Long> sizes = new HashMap<>();
        packageMapper.selectList(Wrappers.<OrderPackageEntity>lambdaQuery()
                        .in(OrderPackageEntity::getSnapshotId, snapshotIds))
                .forEach(pkg -> sizes.merge(pkg.getSnapshotId(), pkg.getPackageSize() == null ? 0L : pkg.getPackageSize(), Math::max));
        rows.forEach(row -> row.setPackageBytes(row.getSnapshotId() == null ? null : sizes.get(row.getSnapshotId())));
        return rows;
    }

    public record OrderInput(Long sourceOrderId, Long snapshotId) {
        public OrderInput { if (sourceOrderId == null || sourceOrderId < 1) throw new IllegalArgumentException("源订单ID非法"); }
    }

    public record FileInput(Long sourceOrderId, String assetIdentity, String step, Long bytesTotal,
                            String platform, String bucket, String objectKey, String versionIdentity, String tempPath) {
        public FileInput { if (assetIdentity == null || assetIdentity.isBlank() || step == null || step.isBlank()) throw new IllegalArgumentException("任务文件明细非法"); }
    }

    private JobEntity required(long id) {
        JobEntity entity = mapper.selectById(id);
        if (entity == null) throw new IllegalArgumentException("任务不存在");
        return entity;
    }

    private static void requireRestore(JobEntity entity) {
        if (!"RESTORE".equals(entity.getJobType())) throw new IllegalArgumentException("恢复任务不存在");
    }

    private Job viewWithEvidence(JobEntity entity) {
        List<JobOrderEntity> orderRows = orderMapper == null ? List.of() : listOrders(entity.getId());
        List<JobFileEntity> fileRows = fileMapper == null ? List.of() : listFiles(entity.getId());
        // 恢复任务同时保存 ZIP 聚合步骤和 ZIP 内资产步骤；任务列表容量必须只统计压缩包，
        // 否则会将压缩后容量与文件原始容量相加，产生双重统计。
        List<JobFileEntity> progressFileRows = "RESTORE".equals(entity.getJobType())
                ? fileRows.stream().filter(row -> "RESTORE_UPLOAD".equals(row.getStep())).toList()
                : fileRows;
        Job job = view(entity,
                Math.max(orderTotal(entity), orderRows.size()),
                Math.max(fileTotal(entity), progressFileRows.size()));
        job.setOrderCompleted((int) orderRows.stream().filter(row -> "SUCCEEDED".equals(row.getStatus()) || "SIMULATED".equals(row.getStatus())).count());
        job.setOrderFailed((int) orderRows.stream().filter(row -> "FAILED".equals(row.getStatus()) || "SIMULATION_FAILED".equals(row.getStatus())).count());
        job.setOrderSkipped((int) orderRows.stream().filter(row -> "SKIPPED".equals(row.getStatus())).count());
        job.setFileCompleted((int) progressFileRows.stream().filter(row -> "SUCCEEDED".equals(row.getStatus())).count());
        job.setFileFailed((int) progressFileRows.stream().filter(row -> "FAILED".equals(row.getStatus())).count());
        job.setBytesTotal(progressFileRows.stream().mapToLong(row -> row.getBytesTotal() == null ? 0L : row.getBytesTotal()).sum());
        job.setBytesCompleted(progressFileRows.stream().mapToLong(row -> row.getBytesDone() == null ? 0L : row.getBytesDone()).sum());
        job.setExecutionMode(configText(entity, "executionMode", "REAL"));
        job.setConflictPolicy(configText(entity, "conflictPolicy", null));
        // Manifest 成功与本地模拟制品成功是两个阶段：已有预计文件数说明 Manifest 已成功返回。
        job.setManifestSucceeded((int) orderRows.stream().filter(row -> row.getExpectedFileCount() != null).count());
        job.setManifestFailed((int) orderRows.stream().filter(row -> "SIMULATION_FAILED".equals(row.getStatus())
                && row.getExpectedFileCount() == null).count());
        job.setExpectedFileTotal(orderRows.stream().mapToInt(row -> row.getExpectedFileCount() == null ? 0 : row.getExpectedFileCount()).sum());
        job.setExpectedBytes(orderRows.stream().mapToLong(row -> row.getExpectedBytes() == null ? 0L : row.getExpectedBytes()).sum());
        Map<Long, Long> packageBytes = new HashMap<>();
        java.util.List<Long> snapshotIds = orderRows.stream().map(JobOrderEntity::getSnapshotId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        if (packageMapper != null && !snapshotIds.isEmpty()) {
            packageMapper.selectList(Wrappers.<OrderPackageEntity>lambdaQuery()
                            .in(OrderPackageEntity::getSnapshotId, snapshotIds))
                    .forEach(pkg -> packageBytes.merge(pkg.getSnapshotId(), pkg.getPackageSize() == null ? 0L : pkg.getPackageSize(), Math::max));
        }
        orderRows.forEach(row -> row.setPackageBytes(row.getSnapshotId() == null ? null : packageBytes.get(row.getSnapshotId())));
        job.setPackageBytes(orderRows.stream().mapToLong(row -> row.getPackageBytes() == null ? 0L : row.getPackageBytes()).sum());
        job.setTargetStorageVolumeId(entity.getTargetStorageVolumeId());
        job.setStorageVolumeName(orderRows.stream().map(JobOrderEntity::getStorageVolumeName)
                .filter(value -> value != null && !value.isBlank()).distinct().collect(java.util.stream.Collectors.joining("、")));
          job.setStorageTier(orderRows.stream().map(JobOrderEntity::getStorageTier)
                  .filter(value -> value != null && !value.isBlank()).distinct().collect(java.util.stream.Collectors.joining("、")));
          job.setCurrentStorageVolumeName(orderRows.stream().map(JobOrderEntity::getCurrentStorageVolumeName).filter(value->value!=null&&!value.isBlank()).distinct().collect(java.util.stream.Collectors.joining("、")));
          job.setCurrentStorageTier(orderRows.stream().map(JobOrderEntity::getCurrentStorageTier).filter(value->value!=null&&!value.isBlank()).distinct().collect(java.util.stream.Collectors.joining("、")));
          job.setCurrentMountStatus(orderRows.stream().map(JobOrderEntity::getCurrentMountStatus).filter(value->value!=null&&!value.isBlank()).distinct().collect(java.util.stream.Collectors.joining("、")));
          job.setCurrentOperationStatus(orderRows.stream().map(JobOrderEntity::getCurrentOperationStatus).filter(value->value!=null&&!value.isBlank()).distinct().collect(java.util.stream.Collectors.joining("、")));
          return job;
    }

    private static Job view(JobEntity entity, int orderTotal, int fileTotal) {
        Job job = new Job(entity.getId());
        job.setJobType(entity.getJobType());
        job.setJobNo(entity.getJobNo());
        job.setRequestedBy(entity.getRequestedBy());
        job.setRequestedAt(toInstant(entity.getRequestedAt()));
        job.setStartedAt(toInstant(entity.getStartedAt()));
        job.setFinishedAt(toInstant(entity.getFinishedAt()));
        job.setState(JobState.valueOf(entity.getStatus()));
        job.setFencingToken(entity.getFencingToken() == null ? 0 : entity.getFencingToken());
        job.setLeaseOwner(entity.getLeaseOwner());
        job.setLeaseUntil(entity.getLeaseUntil() == null ? null : entity.getLeaseUntil().toInstant(ZoneOffset.UTC));
        job.setOrderTotal(orderTotal);
        job.setFileTotal(fileTotal);
        job.setFileCompleted(entity.getProgressCurrent() == null ? 0 : entity.getProgressCurrent().intValue());
        job.setFailureCode(entity.getFailureCode());
        job.setFailureEvidence(entity.getFailureMessage());
        job.setResultJson(entity.getResultJson());
        job.setCancelRequested(Boolean.TRUE.equals(entity.getCancelRequested()));
        return job;
    }

    private static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(java.time.ZoneId.systemDefault()).toInstant();
    }

    private static int orderTotal(JobEntity entity) {
        int explicit = parseTotal(entity, "orderTotal");
        if (explicit > 0) return explicit;
        try {
            JsonNode config = JSON.readTree(entity.getConfigSnapshotJson());
            JsonNode ids = config.path("sourceOrderIds");
            if (!ids.isArray()) ids = config.path("orderSnapshotIds");
            return ids.isArray() ? ids.size() : 0;
        } catch (Exception ignored) {
            return 0;
        }
    }
    private static int fileTotal(JobEntity entity) { return parseTotal(entity, "fileTotal"); }
    private static String configText(JobEntity entity, String key, String fallback) {
        try {
            JsonNode value = JSON.readTree(entity.getConfigSnapshotJson()).path(key);
            return value.isTextual() && !value.asText().isBlank() ? value.asText() : fallback;
        } catch (Exception ignored) { return fallback; }
    }
    private static int parseTotal(JobEntity entity, String key) {
        String json = entity.getConfigSnapshotJson();
        if (json == null) return 0;
        String marker = "\"" + key + "\":";
        int start = json.indexOf(marker);
        if (start < 0) return 0;
        start += marker.length();
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
        try { return Integer.parseInt(json.substring(start, end)); } catch (RuntimeException e) { return 0; }
    }
}
