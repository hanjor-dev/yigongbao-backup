package com.yigongbao.backup.job;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobServiceTest {
    @Test
    void oldExecutorCannotWriteAfterLeaseRecovery() {
        JobService service = new JobService(Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
        Job job = service.create(1, 1);
        Job first = service.claim(job.getId(), "worker-a", Duration.ZERO);
        long firstToken = first.getFencingToken();
        Job second = service.recoverExpired(job.getId(), "worker-b", Duration.ofMinutes(1));

        assertThat(second.getFencingToken()).isGreaterThan(firstToken);
        assertThatThrownBy(() -> service.heartbeat(job.getId(), "worker-a", firstToken, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void illegalTransitionAndTerminalRecoveryAreRejected() {
        JobService service = new JobService();
        Job job = service.create(0, 0);
        assertThatThrownBy(() -> service.transition(job.getId(), "none", 0, JobState.SUCCEEDED))
                .isInstanceOf(IllegalStateException.class);
        Job claimed = service.claim(job.getId(), "worker", Duration.ofMinutes(1));
        service.transition(job.getId(), "worker", claimed.getFencingToken(), JobState.SUCCEEDED);
        assertThatThrownBy(() -> service.recoverExpired(job.getId(), "other", Duration.ofMinutes(1)))
                .isInstanceOf(IllegalStateException.class);
    }
}
