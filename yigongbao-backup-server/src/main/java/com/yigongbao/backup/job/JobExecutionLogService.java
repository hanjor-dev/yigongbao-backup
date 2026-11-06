package com.yigongbao.backup.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 持久化可在管理页查询的任务执行日志。
 *
 * <p>日志写入采用尽力而为策略：审计日志存储故障不能反向中断已在执行的恢复流程，
 * 但会在服务日志中输出明确错误供运维处理。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class JobExecutionLogService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern URL = Pattern.compile("(?i)https?://\\S+");
    private static final Pattern SENSITIVE_VALUE = Pattern.compile(
            "(?i)(signature|secret|token|credential|authorization|password)\\s*[=:]\\s*[^,;\\s]+"
    );
    private static final Pattern SENSITIVE_JSON_VALUE = Pattern.compile(
            "(?i)(\\\"(?:signature|secret|token|credential|authorization|password)\\\"\\s*:\\s*)\\\"[^\\\"]*\\\""
    );

    private final JobExecutionLogMapper mapper;

    /** 写入信息级业务事件。 */
    public void info(long jobId, Long sourceOrderId, String eventCode, String stage,
                     String message, Map<String, ?> details) {
        record(jobId, sourceOrderId, "INFO", eventCode, stage, message, details);
    }

    /** 写入告警级业务事件。 */
    public void warn(long jobId, Long sourceOrderId, String eventCode, String stage,
                     String message, Map<String, ?> details) {
        record(jobId, sourceOrderId, "WARN", eventCode, stage, message, details);
    }

    /** 写入错误级业务事件。 */
    public void error(long jobId, Long sourceOrderId, String eventCode, String stage,
                      String message, Map<String, ?> details) {
        record(jobId, sourceOrderId, "ERROR", eventCode, stage, message, details);
    }

    /**
     * 写入一条已脱敏的日志。任何日志写入异常均只降级到应用日志，不能影响业务任务。
     */
    public void record(long jobId, Long sourceOrderId, String level, String eventCode, String stage,
                       String message, Map<String, ?> details) {
        try {
            JobExecutionLogEntity row = new JobExecutionLogEntity();
            row.setJobId(jobId);
            row.setSourceOrderId(sourceOrderId);
            row.setLogLevel(level);
            row.setEventCode(eventCode);
            row.setStage(stage);
            row.setMessage(sanitize(message));
            row.setDetailJson(sanitize(JSON.writeValueAsString(details == null ? Map.of() : details)));
            row.setOccurredAt(LocalDateTime.now());
            mapper.insert(row);
        } catch (Exception exception) {
            log.error("任务执行日志持久化失败｜任务ID={}｜订单ID={}｜事件={}｜原因={}",
                    jobId, sourceOrderId, eventCode, exception.getMessage(), exception);
        }
    }

    /** 删除 URL 及常见认证字段的值，避免页面日志泄露对象存储访问凭据。 */
    static String sanitize(String value) {
        if (value == null) return "";
        String hiddenUrl = URL.matcher(value).replaceAll("[URL_REDACTED]");
        String hiddenTextValue = SENSITIVE_VALUE.matcher(hiddenUrl).replaceAll("$1=[REDACTED]");
        return SENSITIVE_JSON_VALUE.matcher(hiddenTextValue).replaceAll("$1\"[REDACTED]\"");
    }

}
