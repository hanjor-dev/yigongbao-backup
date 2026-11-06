package com.yigongbao.backup.job;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 验证任务及订单日志均通过受控的分页查询读取。 */
class JobExecutionLogQueryServiceTest {

    @Test
    void rejectsInvalidPageParametersBeforeQueryingLogs() {
        JobExecutionLogQueryService service = new JobExecutionLogQueryService(mock(JobExecutionLogMapper.class));

        assertThatThrownBy(() -> service.page(99L, 31L, 0L, 20L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("日志分页参数非法");
    }

    @Test
    void returnsTaskScopedLogPage() {
        JobExecutionLogMapper mapper = mock(JobExecutionLogMapper.class);
        when(mapper.selectPage(any(Page.class), any())).thenAnswer(invocation -> {
            Page<JobExecutionLogEntity> page = invocation.getArgument(0);
            page.setTotal(1L);
            page.setRecords(java.util.List.of(new JobExecutionLogEntity()));
            return page;
        });
        JobExecutionLogQueryService service = new JobExecutionLogQueryService(mapper);

        JobExecutionLogQueryService.LogPage result = service.page(99L, null, 1L, 20L);

        org.assertj.core.api.Assertions.assertThat(result.total()).isEqualTo(1L);
        org.assertj.core.api.Assertions.assertThat(result.records()).hasSize(1);
    }
}
