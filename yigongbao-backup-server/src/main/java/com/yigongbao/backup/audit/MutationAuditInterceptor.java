package com.yigongbao.backup.audit;

import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

/** 为未显式记录业务事件的写接口提供统一审计兜底。 */
@Component
@RequiredArgsConstructor
public class MutationAuditInterceptor implements HandlerInterceptor {
    private static final Set<String> MUTATING = Set.of("POST", "PUT", "PATCH", "DELETE");
    private final AuditLogService audit;

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception exception) {
        if (!MUTATING.contains(request.getMethod())
                || Boolean.TRUE.equals(request.getAttribute(AuditLogService.AUDIT_RECORDED))) return;
        // 登录有独立成功/失败事件；避免同一次认证尝试重复入链。
        if (request.getRequestURI().endsWith("/api/v1/auth/login")) return;
        Long actor = null;
        String role = null;
        try {
            if (StpUtil.isLogin()) {
                actor = StpUtil.getLoginIdAsLong();
                role = StpUtil.hasRole("ADMIN") ? "ADMIN" : "USER";
            }
        } catch (RuntimeException ignored) { }
        String result = response.getStatus() < 400 && exception == null ? "OK" : "FAILED";
        String failure = exception == null ? (response.getStatus() < 400 ? null : "HTTP " + response.getStatus())
                : exception.getClass().getSimpleName() + ": " + exception.getMessage();
        audit.appendActor(request, actor, role, "HTTP_MUTATION", "API",
                truncate(request.getRequestURI(), 128), null, result, failure);
    }

    private static String truncate(String value, int max) {
        if (value == null) return "-";
        return value.substring(0, Math.min(value.length(), max));
    }
}
