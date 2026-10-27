package com.yigongbao.backup.job;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PersistentJobCoordinatorSafetyTest {
    @BeforeAll
    static void initializeMybatisMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), JobEntity.class);
    }
    @Test
    void rejectsOldFencingTokenBeforeWritingProgress() {
        JobMapper mapper = mock(JobMapper.class);
        JobEntity running = running(1L, "new-worker", 12L);
        when(mapper.selectById(1L)).thenReturn(running);
        PersistentJobCoordinator coordinator = new PersistentJobCoordinator(mapper);

        assertThatThrownBy(() -> coordinator.safePoint(1L, "old-worker", 11L, "UPLOAD", 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fencing token");
        verify(mapper, never()).update(any(), any());
    }

    @Test
    void cancellationBecomesTerminalAtNextSafePoint() {
        JobMapper mapper = mock(JobMapper.class);
        JobEntity running = running(2L, "worker", 3L); running.setCancelRequested(true);
        when(mapper.selectById(2L)).thenReturn(running); when(mapper.update(any(), any())).thenReturn(1);
        PersistentJobCoordinator coordinator = new PersistentJobCoordinator(mapper);

        assertThatThrownBy(() -> coordinator.safePoint(2L, "worker", 3L, "NEXT_ORDER", 1))
                .isInstanceOf(JobCancelledException.class);
        verify(mapper).update(isNull(), any());
    }

    @Test
    void expiredLeaseIsRecoveredAndFailureUsesRetryBackoff() {
        JobMapper mapper = mock(JobMapper.class);
        JobEntity expired = running(3L, "dead-worker", 5L); expired.setVersion(0);
        expired.setLeaseUntil(LocalDateTime.now().minusSeconds(1)); expired.setRetryCount(0);
        when(mapper.selectList(any())).thenReturn(List.of(expired)); when(mapper.update(any(), any())).thenReturn(1);
        PersistentJobCoordinator coordinator = new PersistentJobCoordinator(mapper);

        assertThat(coordinator.recoverExpired()).isEqualTo(1);

        JobEntity retrying = running(4L, "worker", 8L); retrying.setRetryCount(0);
        when(mapper.selectById(4L)).thenReturn(retrying);
        coordinator.failOrRetry(4L, "worker", 8L, 3, new IllegalStateException("temporary"));
        verify(mapper, times(2)).update(isNull(), any());
    }

    private static JobEntity running(long id, String owner, long token) {
        JobEntity job = new JobEntity(); job.setId(id); job.setStatus("RUNNING"); job.setLeaseOwner(owner);
        job.setFencingToken(token); job.setCancelRequested(false); return job;
    }
}
