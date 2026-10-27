package com.yigongbao.backup.job;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/** 任务中心数据库实现；领取使用版本、租约和 fencing token 条件更新。 */
@Service
public class PersistentJobService {
    private final JobMapper mapper;
    private final JobOrderMapper orderMapper;
    private final JobFileMapper fileMapper;

    /** 调度批次统计值，避免将远端订单明细在创建任务前重复拉取。 */
    public record JobInputBatch(int orderTotal, int fileTotal) {}

    public PersistentJobService(JobMapper mapper) { this(mapper, null, null); }

    @Autowired
    public PersistentJobService(JobMapper mapper, JobOrderMapper orderMapper, JobFileMapper fileMapper) {
        this.mapper = mapper; this.orderMapper = orderMapper; this.fileMapper = fileMapper;
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

    public Job get(long id) {
        JobEntity entity = required(id);
        return view(entity, orderTotal(entity), fileTotal(entity));
    }

    public Job claim(long id, String owner, Duration lease) {
        if (owner == null || owner.isBlank() || lease == null || lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("任务租约参数非法");
        }
        JobEntity entity = required(id);
        LocalDateTime now = LocalDateTime.now();
        boolean available = JobState.CREATED.name().equals(entity.getStatus())
                || (JobState.RUNNING.name().equals(entity.getStatus()) && entity.getLeaseUntil() != null
                && !entity.getLeaseUntil().isAfter(now));
        if (!available) throw new IllegalStateException("任务不可领取");
        UpdateWrapper<JobEntity> update = new UpdateWrapper<>();
        update.eq("id", id).eq("version", entity.getVersion())
                .and(w -> w.eq("status", JobState.CREATED.name())
                        .or().eq("status", JobState.RUNNING.name()).le("lease_until", now))
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
        return orderMapper.selectList(Wrappers.<JobOrderEntity>lambdaQuery().eq(JobOrderEntity::getJobId, jobId)
                .orderByAsc(JobOrderEntity::getId));
    }

    public List<JobFileEntity> listFiles(long jobId) {
        if (fileMapper == null) throw new IllegalStateException("任务文件明细持久化未配置");
        return fileMapper.selectList(Wrappers.<JobFileEntity>lambdaQuery().eq(JobFileEntity::getJobId, jobId)
                .orderByAsc(JobFileEntity::getId));
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

    private static Job view(JobEntity entity, int orderTotal, int fileTotal) {
        Job job = new Job(entity.getId());
        job.setJobType(entity.getJobType());
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
        return job;
    }

    private static int orderTotal(JobEntity entity) { return parseTotal(entity, "orderTotal"); }
    private static int fileTotal(JobEntity entity) { return parseTotal(entity, "fileTotal"); }
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
