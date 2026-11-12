package com.yigongbao.backup.cleanup;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * COS 清理任务的数据库实现。
 *
 * <p>任务、对象明细和删除 permit 均写入数据库；状态迁移使用条件更新，
 * 因此多实例领取、进程重启和重复请求不会依赖进程内 Map。</p>
 */
@Service
public class CleanupTaskService {
    private final CleanupTaskMapper taskMapper;
    private final CleanupItemMapper itemMapper;
    private final DeletePermitMapper permitMapper;

    @Autowired
    public CleanupTaskService(CleanupTaskMapper taskMapper, CleanupItemMapper itemMapper,
                             DeletePermitMapper permitMapper) {
        this.taskMapper = taskMapper;
        this.itemMapper = itemMapper;
        this.permitMapper = permitMapper;
    }

    /** 以幂等键创建任务；相同请求重复提交直接返回第一次任务。 */
    @Transactional
    public CleanupTaskEntity create(CreateInput input, long actor) {
        validate(input);
        CleanupTaskEntity existing = taskMapper.findByIdempotencyKey(input.idempotencyKey());
        if (existing != null) {
            if (!sameRequest(existing, input)) throw new IllegalStateException("清理任务幂等键对应不同请求");
            return existing;
        }
        CleanupTaskEntity task = new CleanupTaskEntity();
        task.setTaskNo(blank(input.taskNo()) ? "CLEANUP-" + UUID.randomUUID() : input.taskNo());
        task.setIdempotencyKey(input.idempotencyKey());
        task.setPolicyVersion(input.policyVersion());
        task.setMode(input.mode());
        task.setStatus("CREATED");
        task.setSnapshotHash(input.snapshotHash());
        task.setObjectSetHash(input.objectSetHash());
        task.setEstimatedBytes(Math.max(input.estimatedBytes(), 0));
        task.setActualBytes(0L);
        task.setFencingToken(0L);
        task.setCreateBy(actor);
        task.setUpdateBy(actor);
        taskMapper.insert(task);
        for (ItemInput item : input.items()) {
            CleanupItemEntity entity = new CleanupItemEntity();
            entity.setCleanupTaskId(task.getId());
            entity.setPlatform(item.platform());
            entity.setBucket(item.bucket());
            entity.setObjectKey(item.objectKey());
            entity.setVersionIdentity(item.versionIdentity());
            entity.setSourceFileId(item.sourceFileId());
            entity.setSharedReferenceStatus(item.sharedReferenceStatus());
            entity.setDeletionStatus("PENDING");
            entity.setVersionBytes(Math.max(item.versionBytes(), 0));
            entity.setRetryCount(0);
            entity.setCreateBy(actor);
            entity.setUpdateBy(actor);
            itemMapper.insert(entity);
        }
        return task;
    }

    /** 将线上评估结果固化为本地清理任务。 */
    @Transactional
    public CleanupTaskEntity create(CleanupClient.Evaluation evaluation, CleanupClient.CleanupRequest request,
                                    long actor, String idempotencyKey) {
        if (evaluation == null || request == null || blank(idempotencyKey)) {
            throw new IllegalArgumentException("清理评估或幂等键不能为空");
        }
        if (blank(evaluation.snapshotHash()) || blank(evaluation.objectSetHash())
                || evaluation.objects().stream().anyMatch(item -> item == null || blank(item.objectKey())
                    || blank(item.versionIdentity())))
            throw new IllegalStateException("线上清理评估缺少快照摘要、对象集合摘要或精确对象版本");
        List<ItemInput> items = evaluation.objects().stream()
                .map(item -> new ItemInput(blank(item.platform()) ? "COS" : item.platform(),
                        blank(item.bucket()) ? "UNKNOWN" : item.bucket(), item.objectKey(), item.versionIdentity(),
                        item.sourceFileId(), blank(item.sharedReferenceStatus()) ? "UNVERIFIED" : item.sharedReferenceStatus(),
                        item.versionBytes()))
                .toList();
        return create(new CreateInput(evaluation.taskId(), idempotencyKey, request.olderThanDays(),
                request.dryRun() ? "DRY_RUN" : "ACTUAL", evaluation.snapshotHash(), evaluation.objectSetHash(),
                evaluation.estimatedBytes(), items), actor);
    }

    public List<CleanupTaskEntity> list() {
        return taskMapper.selectList(Wrappers.<CleanupTaskEntity>lambdaQuery()
                .orderByDesc(CleanupTaskEntity::getId));
    }

    public CleanupTaskEntity get(long id) {
        CleanupTaskEntity task = taskMapper.selectById(id);
        if (task == null) throw new IllegalArgumentException("清理任务不存在");
        return task;
    }

    public CleanupTaskEntity findByTaskNo(String taskNo) {
        return blank(taskNo) ? null : taskMapper.findByTaskNo(taskNo);
    }

    public java.util.Set<String> completedKeys(String taskNo) {
        CleanupTaskEntity task = findByTaskNo(taskNo);
        if (task == null) return java.util.Set.of();
        return listItems(task.getId()).stream()
                .filter(item -> "DELETED".equals(item.getDeletionStatus()) || "SKIPPED".equals(item.getDeletionStatus()))
                .map(CleanupItemEntity::getObjectKey)
                .collect(java.util.stream.Collectors.toSet());
    }

    public java.util.Set<String> completedObjectVersions(String taskNo) {
        CleanupTaskEntity task = findByTaskNo(taskNo);
        if (task == null) return java.util.Set.of();
        return listItems(task.getId()).stream()
                .filter(item -> "DELETED".equals(item.getDeletionStatus()) || "SKIPPED".equals(item.getDeletionStatus()))
                .map(item -> exactIdentity(item.getObjectKey(), item.getVersionIdentity()))
                .collect(java.util.stream.Collectors.toSet());
    }

    public List<CleanupItemEntity> listItems(long taskId) {
        get(taskId);
        return itemMapper.selectList(Wrappers.<CleanupItemEntity>lambdaQuery()
                .eq(CleanupItemEntity::getCleanupTaskId, taskId).orderByAsc(CleanupItemEntity::getId));
    }

    /** CREATED/RUNNING -> PAUSED；重复暂停是幂等的，终态不可回退。 */
    @Transactional
    public CleanupTaskEntity pause(long id, long actor) {
        return transition(id, actor, "PAUSED", List.of("CREATED", "RUNNING"), true);
    }

    /** PAUSED -> RUNNING；重复恢复是幂等的，终态不可恢复。 */
    @Transactional
    public CleanupTaskEntity resume(long id, long actor) {
        return transition(id, actor, "RUNNING", List.of("PAUSED"), true);
    }

    @Transactional
    public DeletePermitEntity savePermit(long taskId, CleanupClient.Permit permit, PermitEvidence evidence,
                                         long actor) {
        CleanupTaskEntity task = get(taskId);
        if (permit == null || blank(permit.permitId()) || !taskIdEquals(permit.taskId(), task)) {
            throw new IllegalArgumentException("清理 permit 与任务不匹配");
        }
        DeletePermitEntity existing = permitMapper.findByPermitId(permit.permitId());
        if (existing != null) return existing;
        if ("PAUSED".equals(task.getStatus()) || isTerminal(task.getStatus())) {
            throw new IllegalStateException("当前任务状态不允许保存 permit");
        }
        DeletePermitEntity entity = new DeletePermitEntity();
        entity.setPermitId(permit.permitId());
        entity.setCleanupTaskId(taskId);
        entity.setAttemptNo(evidence == null ? 1 : Math.max(evidence.attemptNo(), 1));
        entity.setStatus(normalizePermitStatus(permit.status()));
        if (evidence != null) {
            entity.setSnapshotHash(evidence.snapshotHash());
            entity.setObjectSetHash(evidence.objectSetHash());
            entity.setFencingToken(evidence.fencingToken());
            entity.setReceiptId(evidence.receiptId());
            entity.setReceiptVersion(evidence.receiptVersion());
            entity.setPackageHash(evidence.packageHash());
            entity.setNonce(evidence.nonce());
        }
        entity.setIssuedAt(LocalDateTime.now());
        if (permit.expiresAt() != null) entity.setExpiresAt(com.yigongbao.backup.common.time.BusinessTimeCodec.toLocalDateTime(permit.expiresAt()));
        entity.setCreateBy(actor);
        entity.setUpdateBy(actor);
        permitMapper.insert(entity);
        return entity;
    }

    public List<DeletePermitEntity> listPermits(long taskId) {
        get(taskId);
        return permitMapper.selectList(Wrappers.<DeletePermitEntity>lambdaQuery()
                .eq(DeletePermitEntity::getCleanupTaskId, taskId).orderByDesc(DeletePermitEntity::getAttemptNo));
    }

    /** 从已冻结任务构造线上授权证据，禁止使用 taskId 派生的占位摘要。 */
    public CleanupClient.PermitRequest permitRequest(long taskId) {
        CleanupTaskEntity task = get(taskId);
        if (blank(task.getSnapshotHash()) || blank(task.getObjectSetHash()))
            throw new IllegalStateException("冻结清理任务缺少快照或对象集合摘要");
        int attempt = listPermits(taskId).stream().map(DeletePermitEntity::getAttemptNo)
                .filter(java.util.Objects::nonNull).max(Integer::compareTo).orElse(0) + 1;
        String verificationDigest = sha256(listItems(taskId).stream()
                .map(item -> exactIdentity(item.getObjectKey(), item.getVersionIdentity()))
                .sorted().collect(java.util.stream.Collectors.joining("\n")));
        return new CleanupClient.PermitRequest(attempt, task.getSnapshotHash(), task.getObjectSetHash(),
                String.valueOf(task.getFencingToken() == null ? 0L : task.getFencingToken()),
                task.getTaskNo(), Math.max(task.getVersion() == null ? 0 : task.getVersion(), 1), verificationDigest);
    }

    @Transactional
    public DeletePermitEntity claimPermit(long permitId, String owner, Duration lease) {
        if (blank(owner) || lease == null || lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("permit 领取参数非法");
        }
        DeletePermitEntity permit = permitMapper.selectById(permitId);
        if (permit == null) throw new IllegalArgumentException("清理 permit 不存在");
        LocalDateTime now = LocalDateTime.now();
        if (permit.getExpiresAt() != null && !permit.getExpiresAt().isAfter(now)) {
            expirePermit(permit.getId());
            throw new IllegalStateException("清理 permit 已过期");
        }
        if ("CLAIMED".equals(permit.getStatus()) && owner.equals(permit.getClaimOwner())
                && permit.getClaimUntil() != null && permit.getClaimUntil().isAfter(now)) return permit;
        long token = (permit.getLocalFencingToken() == null ? 0L : permit.getLocalFencingToken()) + 1L;
        UpdateWrapper<DeletePermitEntity> update = new UpdateWrapper<>();
        update.eq("id", permitId).eq("version", permit.getVersion())
                .and(w -> w.eq("status", "ISSUED").or(expired -> expired.eq("status", "CLAIMED")
                        .le("claim_until", now)))
                .set("status", "CLAIMED").set("claim_owner", owner)
                .set("claim_until", now.plus(lease)).set("local_fencing_token", token)
                .setSql("version=version+1");
        if (permitMapper.update(null, update) != 1) throw new IllegalStateException("permit 已被其他实例领取");
        return permitMapper.selectById(permitId);
    }

    @Transactional
    public DeletePermitEntity consumePermit(long permitId, String owner, long fencingToken) {
        assertPermitLease(permitId, owner, fencingToken);
        DeletePermitEntity permit = permitMapper.selectById(permitId);
        UpdateWrapper<DeletePermitEntity> update = new UpdateWrapper<>();
        update.eq("id", permitId).eq("version", permit.getVersion()).eq("status", "CLAIMED")
                .eq("claim_owner", owner).eq("local_fencing_token", fencingToken)
                .gt("claim_until", LocalDateTime.now()).set("status", "CONSUMED")
                .set("claim_until", null).setSql("version=version+1");
        if (permitMapper.update(null, update) != 1) throw new IllegalStateException("permit 租约已失效或被其他实例接管");
        return permitMapper.selectById(permitId);
    }

    public void assertPermitLease(long permitId, String owner, long fencingToken) {
        DeletePermitEntity permit = permitMapper.selectById(permitId);
        if (permit == null || !"CLAIMED".equals(permit.getStatus()) || !java.util.Objects.equals(owner, permit.getClaimOwner())
                || permit.getLocalFencingToken() == null || permit.getLocalFencingToken() != fencingToken
                || permit.getClaimUntil() == null || !permit.getClaimUntil().isAfter(LocalDateTime.now())) {
            throw new IllegalStateException("permit 租约或 fencing token 无效");
        }
    }

    @Transactional
    public void renewPermitLease(long permitId, String owner, long fencingToken, Duration lease) {
        if (lease == null || lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("permit 续租时长非法");
        }
        LocalDateTime now = LocalDateTime.now();
        UpdateWrapper<DeletePermitEntity> update = new UpdateWrapper<>();
        update.eq("id", permitId).eq("status", "CLAIMED").eq("claim_owner", owner)
                .eq("local_fencing_token", fencingToken).gt("claim_until", now)
                .set("claim_until", now.plus(lease)).setSql("version=version+1");
        if (permitMapper.update(null, update) != 1) {
            throw new IllegalStateException("permit 租约已失效或被其他实例接管");
        }
    }

    public void validatePermitObjectSet(long taskId, List<String> permitKeys) {
        java.util.Set<String> expected = listItems(taskId).stream().map(CleanupItemEntity::getObjectKey)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        java.util.Set<String> actual = permitKeys == null ? java.util.Set.of() : permitKeys.stream()
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        if (!expected.equals(actual)) throw new IllegalStateException("清理 permit 对象集合与本地冻结任务不匹配");
    }

    /** permit 必须逐项绑定对象键和不可变版本身份，并复述冻结集合摘要。 */
    public void validatePermitObjectSet(long taskId, List<CleanupClient.PermitObject> permitObjects,
                                        String permitObjectSetHash) {
        CleanupTaskEntity task = get(taskId);
        if (blank(permitObjectSetHash) || !java.util.Objects.equals(task.getObjectSetHash(), permitObjectSetHash))
            throw new IllegalStateException("清理 permit 缺少或不匹配冻结对象集合摘要");
        java.util.Set<String> expected = listItems(taskId).stream()
                .map(item -> exactIdentity(item.getObjectKey(), item.getVersionIdentity()))
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        if (permitObjects == null || permitObjects.stream().anyMatch(item -> item == null
                || blank(item.objectKey()) || blank(item.versionIdentity())))
            throw new IllegalStateException("清理 permit 必须绑定每个对象的精确版本身份");
        java.util.Set<String> actual = permitObjects.stream()
                .map(item -> exactIdentity(item.objectKey(), item.versionIdentity()))
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        if (actual.size() != permitObjects.size() || !expected.equals(actual))
            throw new IllegalStateException("清理 permit 对象版本集合与本地冻结任务不匹配");
    }

    @Transactional
    public DeletePermitEntity revokePermit(long permitId) {
        return movePermit(permitId, "ISSUED", "REVOKED", "CLAIMED");
    }

    @Transactional
    public DeletePermitEntity expirePermit(long permitId) {
        return movePermit(permitId, "ISSUED", "EXPIRED", "CLAIMED");
    }

    @Transactional
    public CleanupItemEntity markItem(long itemId, String status, String errorMessage, long actualBytes) {
        CleanupItemEntity item = itemMapper.selectById(itemId);
        if (item == null) throw new IllegalArgumentException("清理对象不存在");
        if (!List.of("DELETED", "FAILED", "SKIPPED").contains(status)) {
            throw new IllegalArgumentException("对象删除状态非法");
        }
        item.setDeletionStatus(status);
        item.setErrorMessage(errorMessage);
        item.setVersionBytes(Math.max(actualBytes, 0));
        if ("FAILED".equals(status)) item.setRetryCount((item.getRetryCount() == null ? 0 : item.getRetryCount()) + 1);
        UpdateWrapper<CleanupItemEntity> update = new UpdateWrapper<>();
        update.eq("id", item.getId()).eq("version", item.getVersion())
                .set("deletion_status", item.getDeletionStatus())
                .set("error_message", item.getErrorMessage())
                .set("version_bytes", item.getVersionBytes())
                .set("retry_count", item.getRetryCount() == null ? 0 : item.getRetryCount())
                .set("update_time", LocalDateTime.now()).setSql("version=version+1");
        if (itemMapper.update(null, update) != 1) throw new IllegalStateException("清理对象状态已被其他实例修改");
        refreshTaskStatus(item.getCleanupTaskId());
        return item;
    }

    @Transactional
    public CleanupItemEntity markItem(String taskNo, String objectKey, String status,
                                      String errorMessage, long actualBytes) {
        CleanupTaskEntity task = findByTaskNo(taskNo);
        if (task == null) return null;
        CleanupItemEntity item = itemMapper.selectOne(Wrappers.<CleanupItemEntity>lambdaQuery()
                .eq(CleanupItemEntity::getCleanupTaskId, task.getId())
                .eq(CleanupItemEntity::getObjectKey, objectKey).last("LIMIT 1"));
        if (item == null) return null;
        return markItem(item.getId(), status, errorMessage, actualBytes);
    }

    @Transactional
    public CleanupItemEntity markItem(String taskNo, String objectKey, String versionIdentity, String status,
                                      String errorMessage, long actualBytes) {
        CleanupTaskEntity task = findByTaskNo(taskNo);
        if (task == null) return null;
        CleanupItemEntity item = itemMapper.selectOne(Wrappers.<CleanupItemEntity>lambdaQuery()
                .eq(CleanupItemEntity::getCleanupTaskId, task.getId())
                .eq(CleanupItemEntity::getObjectKey, objectKey)
                .eq(CleanupItemEntity::getVersionIdentity, versionIdentity).last("LIMIT 1"));
        if (item == null) throw new IllegalStateException("清理对象精确版本不存在于冻结集合");
        return markItem(item.getId(), status, errorMessage, actualBytes);
    }

    private static String exactIdentity(String key, String version) { return key + "\u0000" + version; }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception impossible) { throw new IllegalStateException("SHA-256不可用", impossible); }
    }

    @Transactional
    public CleanupTaskEntity refreshTaskStatus(long taskId) {
        CleanupTaskEntity task = get(taskId);
        List<CleanupItemEntity> items = listItems(taskId);
        String status = "CREATED";
        if (!items.isEmpty()) {
            boolean failed = items.stream().anyMatch(item -> "FAILED".equals(item.getDeletionStatus()));
            boolean pending = items.stream().anyMatch(item -> !List.of("DELETED", "SKIPPED", "FAILED").contains(item.getDeletionStatus()));
            status = pending ? "RUNNING" : failed ? "PARTIAL" : "SUCCEEDED";
        }
        if (!status.equals(task.getStatus()) && !"PAUSED".equals(task.getStatus())) {
            task.setStatus(status);
            task.setActualBytes(items.stream().filter(item -> "DELETED".equals(item.getDeletionStatus()))
                    .mapToLong(item -> item.getVersionBytes() == null ? 0L : item.getVersionBytes()).sum());
            taskMapper.updateById(task);
        }
        return task;
    }

    private CleanupTaskEntity transition(long id, long actor, String target, List<String> from, boolean idempotent) {
        CleanupTaskEntity task = get(id);
        if (target.equals(task.getStatus()) && idempotent) return task;
        UpdateWrapper<CleanupTaskEntity> update = new UpdateWrapper<>();
        update.eq("id", id).eq("version", task.getVersion()).in("status", from)
                .set("status", target).set("update_by", actor).setSql("version=version+1");
        if (taskMapper.update(null, update) != 1) throw new IllegalStateException("清理任务状态已被其他实例修改");
        return get(id);
    }

    private DeletePermitEntity movePermit(long id, String from, String target, String... moreFrom) {
        DeletePermitEntity permit = permitMapper.selectById(id);
        if (permit == null) throw new IllegalArgumentException("清理 permit 不存在");
        List<String> states = new java.util.ArrayList<>(); states.add(from); if (moreFrom != null) states.addAll(List.of(moreFrom));
        UpdateWrapper<DeletePermitEntity> update = new UpdateWrapper<>();
        update.eq("id", id).eq("version", permit.getVersion()).in("status", states)
                .set("status", target).setSql("version=version+1");
        if (permitMapper.update(null, update) != 1) throw new IllegalStateException("permit 状态已被其他实例修改");
        return permitMapper.selectById(id);
    }

    private void expirePermit(DeletePermitEntity permit) {
        try { movePermit(permit.getId(), "ISSUED", "EXPIRED", "CLAIMED"); }
        catch (RuntimeException ignored) { }
    }

    private static void validate(CreateInput input) {
        if (input == null || blank(input.idempotencyKey()) || input.policyVersion() < 0
                || (!"DRY_RUN".equals(input.mode()) && !"ACTUAL".equals(input.mode()))) {
            throw new IllegalArgumentException("清理任务参数非法");
        }
        for (ItemInput item : input.items()) {
            if (item == null || blank(item.platform()) || blank(item.bucket()) || blank(item.objectKey())
                    || blank(item.versionIdentity())) throw new IllegalArgumentException("清理对象参数非法");
        }
    }

    private static boolean sameRequest(CleanupTaskEntity existing, CreateInput input) {
        return java.util.Objects.equals(existing.getPolicyVersion(), input.policyVersion())
                && java.util.Objects.equals(existing.getMode(), input.mode())
                && java.util.Objects.equals(existing.getObjectSetHash(), input.objectSetHash());
    }

    private static String normalizePermitStatus(String status) {
        if (status == null || status.isBlank() || "ACTIVE".equalsIgnoreCase(status)) return "ISSUED";
        return status.toUpperCase(java.util.Locale.ROOT);
    }

    private static boolean taskIdEquals(String remoteTaskId, CleanupTaskEntity task) {
        return task.getTaskNo().equals(remoteTaskId) || String.valueOf(task.getId()).equals(remoteTaskId);
    }

    private static boolean isTerminal(String status) {
        return List.of("SUCCEEDED", "PARTIAL", "FAILED", "CANCELLED").contains(status);
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    public record CreateInput(String taskNo, String idempotencyKey, int policyVersion, String mode,
                              String snapshotHash, String objectSetHash, long estimatedBytes,
                              List<ItemInput> items) {
        public CreateInput {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    public record ItemInput(String platform, String bucket, String objectKey, String versionIdentity,
                            Long sourceFileId, String sharedReferenceStatus, long versionBytes) {}

    public record PermitEvidence(int attemptNo, String snapshotHash, String objectSetHash, String fencingToken,
                                 String receiptId, int receiptVersion, String packageHash, String nonce) {}
}
