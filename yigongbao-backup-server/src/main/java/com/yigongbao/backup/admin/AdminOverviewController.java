package com.yigongbao.backup.admin;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yigongbao.backup.audit.AuditLogEntity;
import com.yigongbao.backup.audit.AuditLogMapper;
import com.yigongbao.backup.common.ApiResponse;
import com.yigongbao.backup.config.ConfigService;
import com.yigongbao.backup.config.PersistentConfigService;
import com.yigongbao.backup.storage.api.StorageProbe;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** 管理员首页概览和只读审计查询；容量不可探测时显式返回 UNKNOWN。 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminOverviewController {
    private final StorageProbe probe;
    private final AuditLogMapper auditLogMapper;
    private final PersistentConfigService configs;

    @Value("${backup.storage.managed-roots:}")
    private String managedRoots;

    @GetMapping("/overview")
    public ApiResponse<Overview> overview() {
        admin();
        List<StorageMetric> storage = Arrays.stream(managedRoots == null ? new String[0] : managedRoots.split(","))
                .map(String::trim).filter(item -> !item.isBlank()).map(this::metric).toList();
        return ApiResponse.ok(new Overview(LocalDateTime.now(), storage, configs.list()), requestId());
    }

    @GetMapping("/audit")
    public ApiResponse<List<AuditLogEntity>> audit(@RequestParam(defaultValue = "100") int limit) {
        admin();
        int safeLimit = Math.max(1, Math.min(limit, 500));
        List<AuditLogEntity> rows = auditLogMapper.selectList(Wrappers.<AuditLogEntity>query()
                .eq("is_deleted", 0).orderByDesc("occurred_at").last("LIMIT " + safeLimit));
        return ApiResponse.ok(rows, requestId());
    }

    private StorageMetric metric(String root) {
        StorageProbe.StorageSnapshot snapshot = probe.probe(Path.of(root));
        long threshold = thresholdFor(root);
        String status = !snapshot.capacityKnown() ? "UNKNOWN" :
                snapshot.totalBytes() == 0 || snapshot.usedBytes() * 100 >= snapshot.totalBytes() * threshold ? "WARN" : "OK";
        return new StorageMetric(root, snapshot.totalBytes(), snapshot.usedBytes(), snapshot.usableBytes(),
                threshold, status, snapshot.capacityKnown());
    }

    private long thresholdFor(String root) {
        String key = root.toLowerCase().contains("cold") ? "storage.threshold.cold.percent" : "storage.threshold.hot.percent";
        ConfigService.Entry entry = configs.get(key);
        if (entry == null) return 85;
        try { return Long.parseLong(entry.value()); } catch (NumberFormatException ignored) { return 85; }
    }

    private static void admin() { StpUtil.checkRole("ADMIN"); }
    private static String requestId() { return UUID.randomUUID().toString(); }

    public record Overview(LocalDateTime observedAt, List<StorageMetric> storage, List<ConfigService.Entry> configs) { }
    public record StorageMetric(String root, long totalBytes, long usedBytes, long availableBytes,
                                long warningThresholdPercent, String status, boolean capacityKnown) { }
}
