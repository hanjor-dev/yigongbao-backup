package com.yigongbao.backup.job;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
    void cancellationBecomesTerminalDuringHeartbeatWait() {
        JobMapper mapper = mock(JobMapper.class);
        JobEntity running = running(5L, "worker", 6L); running.setCancelRequested(true);
        when(mapper.selectById(5L)).thenReturn(running); when(mapper.update(any(), any())).thenReturn(1);
        PersistentJobCoordinator coordinator = new PersistentJobCoordinator(mapper);

        assertThatThrownBy(() -> coordinator.heartbeat(5L, "worker", 6L, 60))
                .isInstanceOf(JobCancelledException.class);
    }
    @Test
    void genericRetryRejectsBackupJobsThatNeedANewFrozenPolicySnapshot() {
        JobMapper mapper = mock(JobMapper.class);
        JobEntity failed = new JobEntity();
        failed.setId(90L); failed.setJobType("ORDER_BACKUP"); failed.setStatus("FAILED");
        when(mapper.selectById(90L)).thenReturn(failed);

        assertThatThrownBy(() -> new PersistentJobCoordinator(mapper).retry(90L, 7L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("重试失败订单");
        verify(mapper, never()).insert(any(JobEntity.class));
    }

    @Test
    void finishingCancellationIsIdempotentForTheSameCompletedAttempt() {
        JobMapper mapper = mock(JobMapper.class);
        JobEntity cancelled = running(8L, "worker", 11L);
        cancelled.setStatus("CANCELLED");
        cancelled.setLeaseOwner(null);
        cancelled.setLeaseUntil(null);
        when(mapper.selectById(8L)).thenReturn(cancelled);

        assertThatCode(() -> new PersistentJobCoordinator(mapper)
                .finishCancellationIfRunning(8L, "worker", 11L))
                .doesNotThrowAnyException();
        verify(mapper, never()).update(any(), any());
    }

    @Test
    void expiredLeaseCannotCommitTaskResultOrTerminalState() {
        JobMapper mapper = mock(JobMapper.class);
        JobEntity expired = running(40L, "worker", 13L);
        expired.setLeaseUntil(LocalDateTime.now().minusSeconds(1));
        when(mapper.selectById(40L)).thenReturn(expired);
        when(mapper.update(any(), any())).thenReturn(1);
        PersistentJobCoordinator coordinator = new PersistentJobCoordinator(mapper);

        assertThatThrownBy(() -> coordinator.storeResult(40L, "worker", 13L, "{}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("租约");
        assertThatThrownBy(() -> coordinator.succeed(40L, "worker", 13L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("租约");
    }

    @Test
    void evidenceWriteRunsOnlyWhileJobRowIsLockedByAnActiveLease() {
        JobMapper mapper = mock(JobMapper.class);
        JobEntity running = running(41L, "worker", 14L);
        when(mapper.findByIdForUpdate(41L)).thenReturn(running);
        Runnable write = mock(Runnable.class);

        new PersistentJobCoordinator(mapper).withinActiveLease(41L, "worker", 14L, write);

        verify(mapper).findByIdForUpdate(41L);
        verify(write).run();
    }

    @Test
    void completedAttemptCanAppendTerminalEvidenceWithTheSameFencingToken() {
        JobMapper mapper = mock(JobMapper.class);
        JobEntity completed = running(43L, "worker", 16L);
        completed.setStatus("SUCCEEDED");
        completed.setLeaseOwner(null);
        completed.setLeaseUntil(null);
        when(mapper.findByIdForUpdate(43L)).thenReturn(completed);
        AtomicBoolean written = new AtomicBoolean(false);
        PersistentJobCoordinator coordinator = new PersistentJobCoordinator(mapper);

        assertThatCode(() -> PersistentJobCoordinator.class
                .getMethod("withinCompletedAttempt", long.class, long.class, Runnable.class)
                .invoke(coordinator, 43L, 16L, (Runnable) () -> written.set(true)))
                .doesNotThrowAnyException();
        assertThat(written).isTrue();
    }

    @Test
    void expiredLockedLeaseCannotWriteEvidence() {
        JobMapper mapper = mock(JobMapper.class);
        JobEntity expired = running(42L, "worker", 15L);
        expired.setLeaseUntil(LocalDateTime.now().minusSeconds(1));
        when(mapper.findByIdForUpdate(42L)).thenReturn(expired);
        Runnable write = mock(Runnable.class);

        assertThatThrownBy(() -> new PersistentJobCoordinator(mapper)
                .withinActiveLease(42L, "worker", 15L, write))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("租约");
        verify(write, never()).run();
    }

    @Test
    void creationCanPersistAStoragePreflightFailureBeforeExecution() {
        JobMapper mapper = mock(JobMapper.class);
        doAnswer(invocation -> {
            JobEntity inserted = invocation.getArgument(0);
            inserted.setId(99L);
            return 1;
        }).when(mapper).insert(any(JobEntity.class));
        when(mapper.update(any(), any())).thenReturn(1);
        PersistentJobCoordinator coordinator = new PersistentJobCoordinator(mapper);

        PersistentJobCoordinator.CreationResult reservation = coordinator.reserve(
                "ORDER_BACKUP", "scheduled:20261111", 0L, "{}");
        JobEntity created = coordinator.completeCreation(reservation.job(), null,
                "STORAGE_VOLUME_UNAVAILABLE", "没有可写的活动热备卷");

        assertThat(created.getStatus()).isEqualTo("CREATED");
        assertThat(created.getTargetStorageVolumeId()).isNull();
        assertThat(created.getFailureCode()).isEqualTo("STORAGE_VOLUME_UNAVAILABLE");
        assertThat(created.getFailureMessage()).isEqualTo("没有可写的活动热备卷");
        verify(mapper).insert(same(created));
    }

    @Test
    void leaseRecoveryPreservesOriginalStoragePreflightFailure() {
        JobMapper mapper = mock(JobMapper.class);
        JobEntity expired = running(31L, "dead-worker", 7L);
        expired.setVersion(0); expired.setRetryCount(0); expired.setTargetStorageVolumeId(null);
        expired.setFailureCode("STORAGE_VOLUME_UNAVAILABLE");
        expired.setFailureMessage("活动热备存储卷不可用或剩余空间不足");
        expired.setLeaseUntil(LocalDateTime.now().minusSeconds(1));
        when(mapper.selectList(any())).thenReturn(List.of(expired));
        when(mapper.update(any(), any())).thenReturn(1);

        assertThat(new PersistentJobCoordinator(mapper).recoverExpired(3)).isEqualTo(1);

        verify(mapper).update(isNull(), argThat(update -> {
            var values = ((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) update)
                    .getParamNameValuePairs();
            return values.containsValue("CREATED") && !values.containsValue("LEASE_EXPIRED");
        }));
    }

    @Test
    void preconditionFailureKeepsItsStableFailureCodeAtTerminalState() {
        JobMapper mapper = mock(JobMapper.class);
        JobEntity running = running(30L, "worker", 6L);
        when(mapper.selectById(30L)).thenReturn(running);
        when(mapper.update(any(), any())).thenReturn(1);

        new PersistentJobCoordinator(mapper).failOrRetry(30L, "worker", 6L, 0,
                new JobPreconditionException("STORAGE_VOLUME_UNAVAILABLE", "没有可写的活动热备卷"));

        verify(mapper).update(isNull(), argThat(update -> {
            var values = ((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) update)
                    .getParamNameValuePairs();
            return values.containsValue("STORAGE_VOLUME_UNAVAILABLE")
                    && values.containsValue("没有可写的活动热备卷")
                    && values.containsValue("FAILED");
        }));
    }

    @Test
    void runningArchiveCancellationIsLeftForHandlerToCleanUpBeforeTerminalState() {
        JobMapper mapper = mock(JobMapper.class);
        JobEntity running = running(20L, "worker", 4L);
        running.setJobType("VOLUME_ARCHIVE");
        running.setCancelRequested(true);
        when(mapper.selectById(20L)).thenReturn(running);
        PersistentJobCoordinator coordinator = new PersistentJobCoordinator(mapper);

        assertThatThrownBy(() -> coordinator.safePoint(20L, "worker", 4L, "VERIFYING", 0))
                .isInstanceOf(JobCancelledException.class);

        verify(mapper, never()).update(any(), any());
    }

    @Test
    void cancellationBecomesTerminalDuringAutomaticLeaseRenewal() {
        JobMapper mapper = mock(JobMapper.class);
        JobEntity running = running(7L, "worker", 10L); running.setCancelRequested(true);
        when(mapper.selectById(7L)).thenReturn(running); when(mapper.update(any(), any())).thenReturn(1);
        PersistentJobCoordinator coordinator = new PersistentJobCoordinator(mapper);

        assertThatThrownBy(() -> coordinator.renewLease(7L, "worker", 10L, 60))
                .isInstanceOf(JobCancelledException.class);
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

    @Test
    void expiredLeaseStopsAfterConfiguredRecoveryLimit() {
        JobMapper mapper = mock(JobMapper.class);
        JobEntity expired = running(6L, "dead-worker", 9L);
        expired.setVersion(0); expired.setRetryCount(3);
        expired.setLeaseUntil(LocalDateTime.now().minusSeconds(1));
        when(mapper.selectList(any())).thenReturn(List.of(expired));
        when(mapper.update(any(), any())).thenReturn(1);

        assertThat(new PersistentJobCoordinator(mapper).recoverExpired(3)).isEqualTo(1);
        verify(mapper).update(isNull(), argThat(update -> update.getSqlSet().contains("status=#{")));
    }

    @Test
    void expiredArchiveLeaseKeepsSameAttemptRecoverableBeyondGenericLimit() {
        JobMapper mapper = mock(JobMapper.class);
        JobEntity expired = running(21L, "dead-worker", 9L);
        expired.setJobType("VOLUME_ARCHIVE");
        expired.setVersion(0); expired.setRetryCount(99);
        expired.setLeaseUntil(LocalDateTime.now().minusSeconds(1));
        when(mapper.selectList(any())).thenReturn(List.of(expired));
        when(mapper.update(any(), any())).thenReturn(1);

        assertThat(new PersistentJobCoordinator(mapper).recoverExpired(3)).isEqualTo(1);

        verify(mapper).update(isNull(), argThat(update -> {
            var values = ((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) update)
                    .getParamNameValuePairs();
            return values.containsValue("CREATED") && !values.containsValue("FAILED");
        }));
    }

    private static JobEntity running(long id, String owner, long token) {
        JobEntity job = new JobEntity(); job.setId(id); job.setStatus("RUNNING"); job.setLeaseOwner(owner);
        job.setFencingToken(token); job.setCancelRequested(false); job.setLeaseUntil(LocalDateTime.now().plusMinutes(5)); return job;
    }
}
