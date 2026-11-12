package com.yigongbao.backup.admin;

import cn.dev33.satoken.stp.StpUtil;
import com.yigongbao.backup.audit.AuditLogEntity;
import com.yigongbao.backup.audit.AuditLogMapper;
import com.yigongbao.backup.audit.AuditLogService;
import com.yigongbao.backup.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** 管理员首页概览和只读审计查询；容量不可探测时显式返回 UNKNOWN。 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminOverviewController {
    private final AdminOverviewService overviewService;
    private final AuditLogMapper auditLogMapper;
    private final AuditLogService audit;

    public AdminOverviewController(AdminOverviewService overviewService, AuditLogMapper auditLogMapper) {
        this(overviewService, auditLogMapper, null);
    }

    @Autowired
    public AdminOverviewController(AdminOverviewService overviewService, AuditLogMapper auditLogMapper,
                                   AuditLogService audit) {
        this.overviewService = overviewService;
        this.auditLogMapper = auditLogMapper;
        this.audit = audit;
    }

    @GetMapping("/overview")
    public ApiResponse<AdminOverviewView> overview(
            @RequestParam(name = "refresh", defaultValue = "false") boolean refresh) {
        admin();
        String requestId = requestId();
        return ApiResponse.ok(overviewService.overview(refresh, requestId), requestId);
    }

    @GetMapping("/overview/trend")
    public ApiResponse<List<AdminOverviewView.BackupTrendPoint>> overviewTrend(
            @RequestParam(name = "period", defaultValue = "WEEK") OverviewTrendPeriod period) {
        admin();
        String requestId = requestId();
        return ApiResponse.ok(overviewService.trend(period), requestId);
    }

    @PostMapping("/overview/alerts/{alertKey}/dismiss")
    public ApiResponse<Void> dismissOverviewAlert(@PathVariable("alertKey") String alertKey,
                                                   HttpServletRequest request) {
        admin();
        long actor=StpUtil.getLoginIdAsLong();
        try {
            overviewService.dismissAlert(alertKey,actor);
            if(audit!=null)audit.appendActor(request,actor,"ADMIN","OVERVIEW_ALERT_DISMISS",
                    "OVERVIEW_ALERT",alertKey,null,"OK",null);
            return ApiResponse.ok(null,requestId());
        } catch(RuntimeException failure) {
            if(audit!=null)audit.appendActor(request,actor,"ADMIN","OVERVIEW_ALERT_DISMISS",
                    "OVERVIEW_ALERT",alertKey,null,"FAILED",failure.getMessage());
            throw failure;
        }
    }

    @GetMapping("/audit")
    public ApiResponse<AuditPage> audit(
            @RequestParam(name = "size", defaultValue = "100") int size,
            @RequestParam(name = "cursorOccurredAt", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime cursorOccurredAt,
            @RequestParam(name = "cursorId", required = false) Long cursorId) {
        admin();
        if ((cursorOccurredAt == null) != (cursorId == null) || cursorId != null && cursorId < 1) {
            throw new IllegalArgumentException("审计游标参数不完整");
        }
        int safeSize = Math.max(1, Math.min(size, 500));
        List<AuditLogEntity> fetched = auditLogMapper.findPage(cursorOccurredAt, cursorId, safeSize + 1);
        boolean hasMore = fetched.size() > safeSize;
        List<AuditLogEntity> records = hasMore ? List.copyOf(fetched.subList(0, safeSize)) : List.copyOf(fetched);
        AuditLogEntity last = records.isEmpty() ? null : records.getLast();
        return ApiResponse.ok(new AuditPage(records, hasMore,
                hasMore ? last.getOccurredAt() : null, hasMore ? last.getId() : null), requestId());
    }

    /** 受控导出最近审计记录；硬上限防止一次请求加载整张审计表。 */
    @GetMapping(value = "/audit/export", produces = "text/csv")
    public ResponseEntity<byte[]> exportAudit(
            @RequestParam(name = "limit", defaultValue = "1000") int limit,
            HttpServletRequest request) {
        admin();
        int safeLimit = Math.max(1, Math.min(limit, 5000));
        if (audit != null) audit.appendActor(request, StpUtil.getLoginIdAsLong(), "ADMIN",
                "AUDIT_EXPORT", "AUDIT_LOG", "LATEST", null, "OK", null);
        List<AuditLogEntity> rows = auditLogMapper.findPage(null, null, safeLimit);
        StringBuilder csv = new StringBuilder("id,occurredAt,actorUserId,actorRoleCode,clientAddress,action,objectType,objectId,resultCode,failureReason\r\n");
        for (AuditLogEntity row : rows) {
            csv.append(row.getId()).append(',').append(csv(row.getOccurredAt())).append(',')
                    .append(csv(row.getActorUserId())).append(',').append(csv(row.getActorRoleCode())).append(',')
                    .append(csv(row.getClientAddress())).append(',').append(csv(row.getAction())).append(',')
                    .append(csv(row.getObjectType())).append(',').append(csv(row.getObjectId())).append(',')
                    .append(csv(row.getResultCode())).append(',').append(csv(row.getFailureReason())).append("\r\n");
        }
        byte[] bytes = csv.toString().getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("audit-log.csv", StandardCharsets.UTF_8).build().toString())
                .contentLength(bytes.length).body(bytes);
    }

    private static String csv(Object value) {
        if (value == null) return "";
        return "\"" + String.valueOf(value).replace("\"", "\"\"").replace("\r", " ").replace("\n", " ") + "\"";
    }

    private static void admin() { StpUtil.checkRole("ADMIN"); }
    private static String requestId() { return UUID.randomUUID().toString(); }

    public record AuditPage(List<AuditLogEntity> records, boolean hasMore,
                            LocalDateTime nextOccurredAt, Long nextId) { }
}
