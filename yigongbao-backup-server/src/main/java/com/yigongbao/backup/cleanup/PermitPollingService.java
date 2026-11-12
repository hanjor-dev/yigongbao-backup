package com.yigongbao.backup.cleanup;

import com.yigongbao.backup.backup.CosObjectClient;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 兼容轮询入口；跨实例互斥由 CleanupService 的数据库 permit 租约保证。 */
public final class PermitPollingService {
    private final CleanupClient client;
    private final CosObjectClient cos;
    private final CleanupTaskService tasks;
    private final CleanupService executor;
    private final Map<String, CleanupService.CleanupResult> fallbackResults = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> fallbackCompletedByTask = new ConcurrentHashMap<>();

    public PermitPollingService(CleanupClient client) { this(client, null, null); }
    public PermitPollingService(CleanupClient client, CosObjectClient cos) { this(client, cos, null); }
    public PermitPollingService(CleanupClient client, CosObjectClient cos, CleanupTaskService tasks) {
        this.client = client;
        this.cos = cos;
        this.tasks = tasks;
        this.executor = new CleanupService(client, cos, tasks);
    }

    public synchronized CleanupService.CleanupResult pollAndExecute(String taskId) {
        if (tasks != null) return executor.execute(taskId);

        // 仅供未接入持久化组件的兼容构造器使用；生产 Bean 始终走数据库租约和 fencing token。
        CleanupClient.Permit permit = client.pollPermit(taskId);
        CleanupService.validate(taskId, permit);
        CleanupService.CleanupResult consumed = fallbackResults.get(permit.permitId());
        if (consumed != null) return consumed;

        Set<String> completed = fallbackCompletedByTask.computeIfAbsent(taskId, ignored -> new HashSet<>());
        int deleted = 0;
        List<String> failed = new ArrayList<>();
        for (String key : new LinkedHashSet<>(permit.objectKeys())) {
            if (completed.contains(key)) continue;
            if (CleanupService.expired(permit)) {
                failed.add(key);
                continue;
            }
            try {
                if (cos != null) cos.delete(key, permit); else client.delete(permit, key);
                completed.add(key);
                deleted++;
            } catch (Exception failure) {
                failed.add(key);
            }
        }
        CleanupService.CleanupResult result = new CleanupService.CleanupResult(permit.permitId(), deleted, failed);
        if (failed.isEmpty()) fallbackResults.put(permit.permitId(), result);
        return result;
    }
}
