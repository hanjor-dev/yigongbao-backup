package com.yigongbao.backup.cleanup;

import com.yigongbao.backup.backup.CosObjectClient;
import com.yigongbao.backup.config.RuntimeConfigService;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 清理协调器：本地只执行线上签发且对象集合匹配的 permit，禁止页面直接删除。 */
public final class CleanupService {
    private static final Duration CLAIM_LEASE = Duration.ofMinutes(5);
    private final CleanupClient client;
    private final CosObjectClient cos;
    private final CleanupTaskService tasks;
    private final RuntimeConfigService runtimeConfig;
    private final String owner = "cleanup-" + UUID.randomUUID();

    public CleanupService(CleanupClient client) { this(client, null, null, null); }
    public CleanupService(CleanupClient client, CosObjectClient cos) { this(client, cos, null, null); }
    public CleanupService(CleanupClient client, CosObjectClient cos, CleanupTaskService tasks) { this(client, cos, tasks, null); }
    public CleanupService(CleanupClient client, CosObjectClient cos, CleanupTaskService tasks,
                          RuntimeConfigService runtimeConfig) {
        this.client = Objects.requireNonNull(client);
        this.cos = cos;
        this.tasks = tasks;
        this.runtimeConfig = runtimeConfig;
    }

    public CleanupClient.Evaluation dryRun(int olderThanDays) {
        if (olderThanDays < 0) throw new IllegalArgumentException("天数不能为负");
        int effective = olderThanDays > 0 ? olderThanDays
                : runtimeConfig == null ? 180 : runtimeConfig.getInt("cleanup.retention-days", 180, 1);
        return client.evaluate(new CleanupClient.CleanupRequest(effective, true));
    }

    public CleanupClient.Evaluation evaluate(CleanupClient.CleanupRequest request) {
        if (request == null || request.olderThanDays() < 1 || request.batchSize() < 1)
            throw new IllegalArgumentException("清理评估策略非法");
        return client.evaluate(request);
    }

    public CleanupResult execute(String taskId) {
        DeletePermitEntity persisted = null;
        Set<String> completed = Set.of();
        long localToken = 0L;
        CleanupTaskEntity task = null;
        CleanupClient.Permit permit;
        if (tasks != null) {
            task = tasks.findByTaskNo(taskId);
            if (task == null) throw new IllegalStateException("本地不存在与 permit 匹配的冻结清理任务");
            permit = client.pollPermit(taskId, tasks.permitRequest(task.getId()));
        } else {
            permit = client.pollPermit(taskId);
        }
        validate(taskId, permit);
        if (tasks != null) {
            tasks.validatePermitObjectSet(task.getId(), permit.objects(), permit.objectSetHash());
            persisted = tasks.savePermit(task.getId(), permit, null, 0L);
            if ("CONSUMED".equals(persisted.getStatus())) return new CleanupResult(permit.permitId(), 0, List.of());
            if ("EXPIRED".equals(persisted.getStatus()) || "REVOKED".equals(persisted.getStatus()))
                throw new IllegalStateException("清理 permit 已失效");
            persisted = tasks.claimPermit(persisted.getId(), owner, CLAIM_LEASE);
            localToken = persisted.getLocalFencingToken() == null ? 0L : persisted.getLocalFencingToken();
            tasks.assertPermitLease(persisted.getId(), owner, localToken);
            completed = tasks.completedObjectVersions(taskId);
        }
        int ok = 0;
        List<String> failed = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (CleanupClient.PermitObject object : permit.objects()) {
            String key = object.objectKey();
            String identity = object.versionIdentity() == null ? key : key + "\u0000" + object.versionIdentity();
            if (!seen.add(identity) || completed.contains(identity)) continue;
            if (expired(permit)) { failed.add(key); continue; }
            if (tasks != null && persisted != null) {
                tasks.renewPermitLease(persisted.getId(), owner, localToken, CLAIM_LEASE);
            }
            try {
                delete(permit, object);
            } catch (Exception failure) {
                if (tasks != null && persisted != null) {
                    tasks.assertPermitLease(persisted.getId(), owner, localToken);
                }
                failed.add(key);
                if (tasks != null) tasks.markItem(taskId, key, object.versionIdentity(), "FAILED", safe(failure), 0L);
                continue;
            }
            if (tasks != null && persisted != null) {
                tasks.assertPermitLease(persisted.getId(), owner, localToken);
            }
            ok++;
            if (tasks != null) tasks.markItem(taskId, key, object.versionIdentity(), "DELETED", null, 0L);
        }
        if (tasks != null && persisted != null && failed.isEmpty()) {
            tasks.consumePermit(persisted.getId(), owner, localToken);
        }
        return new CleanupResult(permit.permitId(), ok, failed);
    }

    static void validate(String taskId, CleanupClient.Permit permit) {
        if (permit == null || !"ACTIVE".equals(permit.status()) || !taskId.equals(permit.taskId())
                || permit.objectKeys() == null) throw new IllegalStateException("清理授权无效");
        if (permit.expiresAt() != null && !permit.expiresAt().isAfter(Instant.now()))
            throw new IllegalStateException("清理授权已过期");
    }

    static boolean expired(CleanupClient.Permit permit) {
        return permit.expiresAt() != null && !permit.expiresAt().isAfter(Instant.now());
    }

    private void delete(CleanupClient.Permit permit, CleanupClient.PermitObject object) throws Exception {
        if (cos != null && object.versionIdentity() != null) cos.delete(object.objectKey(), object.versionIdentity(), permit);
        else if (cos != null) cos.delete(object.objectKey(), permit);
        else client.delete(permit, object.objectKey());
    }

    private static String safe(Exception failure) {
        String message = failure.getMessage();
        return message == null || message.length() > 512 ? "清理失败" : message;
    }

    public record CleanupResult(String permitId, int deleted, List<String> failedKeys) { }
}
