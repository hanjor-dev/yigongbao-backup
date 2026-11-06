package com.yigongbao.backup.job;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.assertj.core.api.Assertions.assertThat;

/** 验证任务执行日志作为独立审计证据持久化，并且不会泄露远程访问凭据。 */
class JobExecutionLogServiceTest {

    @Test
    void storesReadableEventAndRedactsSensitiveRemoteValues() {
        JobExecutionLogMapper mapper = mock(JobExecutionLogMapper.class);
        JobExecutionLogService service = new JobExecutionLogService(mapper);

        service.info(99L, 31L, "FILE_UPLOAD_SUCCEEDED", "文件上传",
                "文件上传成功｜文件名=导板.stl｜地址=https://oss.example.com/a?signature=secret-value",
                Map.of("sessionId", "session-1", "signature", "secret-value"));

        verify(mapper).insert(org.mockito.ArgumentMatchers.argThat((JobExecutionLogEntity row) ->
                row.getJobId().equals(99L)
                        && row.getSourceOrderId().equals(31L)
                        && "INFO".equals(row.getLogLevel())
                        && "FILE_UPLOAD_SUCCEEDED".equals(row.getEventCode())
                        && "文件上传".equals(row.getStage())
                        && !row.getMessage().contains("oss.example.com")
                        && !row.getDetailJson().contains("secret-value")));
    }

    @Test
    void preservesOversizedMessageForCompleteAuditEvidence() {
        JobExecutionLogMapper mapper = mock(JobExecutionLogMapper.class);
        JobExecutionLogService service = new JobExecutionLogService(mapper);

        service.info(99L, null, "TARGETS", "任务领取", "订单号=" + "1,".repeat(1500), Map.of("orderCount", 1500));

        verify(mapper).insert(org.mockito.ArgumentMatchers.argThat((JobExecutionLogEntity row) ->
                row.getMessage().length() > 2000 && row.getMessage().contains("1,1,1")));
    }
}
