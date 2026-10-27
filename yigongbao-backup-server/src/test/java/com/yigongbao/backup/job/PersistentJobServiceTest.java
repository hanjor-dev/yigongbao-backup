package com.yigongbao.backup.job;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PersistentJobServiceTest {
    @Test
    void createsAndClaimsDatabaseTaskWithLease() {
        JobMapper mapper = mock(JobMapper.class);
        doAnswer(invocation -> { JobEntity entity = invocation.getArgument(0); entity.setId(7L); entity.setVersion(0); return 1; })
                .when(mapper).insert(any(JobEntity.class));
        int[] reads = {0};
        when(mapper.selectById(7L)).thenAnswer(invocation -> {
            JobEntity entity = new JobEntity(); entity.setId(7L); entity.setVersion(0);
            entity.setStatus(reads[0]++ == 0 ? "CREATED" : "RUNNING"); entity.setFencingToken(reads[0] <= 1 ? 0L : 1L);
            entity.setConfigSnapshotJson("{\"orderTotal\":2,\"fileTotal\":3}"); return entity;
        });
        when(mapper.update(any(), any())).thenReturn(1);
        PersistentJobService service = new PersistentJobService(mapper);

        assertThat(service.create(2, 3, 9L).getId()).isEqualTo(7L);
        assertThat(service.claim(7L, "worker-1", Duration.ofMinutes(1)).getState()).isEqualTo(JobState.RUNNING);
        verify(mapper).update(any(), any());
    }

    @Test
    void createsOrderAndFileEvidenceInSameService() {
        JobMapper jobs = mock(JobMapper.class); JobOrderMapper orders = mock(JobOrderMapper.class); JobFileMapper files = mock(JobFileMapper.class);
        doAnswer(invocation -> { JobEntity entity = invocation.getArgument(0); entity.setId(8L); entity.setVersion(0); return 1; }).when(jobs).insert(any(JobEntity.class));
        PersistentJobService service = new PersistentJobService(jobs, orders, files);
        service.create(0, 0, 9L,
                List.of(new PersistentJobService.OrderInput(100L, 200L)),
                List.of(new PersistentJobService.FileInput(100L, "asset-1", "DOWNLOAD", 12L, "COS", "bucket", "a/1", "v1", null)));
        verify(orders).insert(any(JobOrderEntity.class));
        verify(files).insert(any(JobFileEntity.class));
    }
}
