package com.yigongbao.backup.admin;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yigongbao.backup.audit.AuditLogEntity;
import com.yigongbao.backup.audit.AuditLogMapper;
import com.yigongbao.backup.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** 管理员首页概览和只读审计查询；容量不可探测时显式返回 UNKNOWN。 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminOverviewController {
    private final AdminOverviewService overviewService;
    private final AuditLogMapper auditLogMapper;

    @GetMapping("/overview")
    public ApiResponse<AdminOverviewView> overview(@RequestParam(name = "refresh", defaultValue = "false") boolean refresh) {
        admin();
        String requestId=requestId();
        return ApiResponse.ok(overviewService.overview(refresh,requestId),requestId);
    }

    @GetMapping("/audit")
    public ApiResponse<List<AuditLogEntity>> audit(@RequestParam(name = "limit", defaultValue = "100") int limit) {
        admin();
        int safeLimit = Math.max(1, Math.min(limit, 500));
        List<AuditLogEntity> rows = auditLogMapper.selectList(Wrappers.<AuditLogEntity>query()
                .eq("is_deleted", 0).orderByDesc("occurred_at").last("LIMIT " + safeLimit));
        return ApiResponse.ok(rows, requestId());
    }

    private static void admin() { StpUtil.checkRole("ADMIN"); }
    private static String requestId() { return UUID.randomUUID().toString(); }

}
