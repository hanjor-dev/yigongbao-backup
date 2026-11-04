package com.yigongbao.backup.job;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class JobEvidenceServiceTest {
    @Test void initializesEveryScheduledOrderBeforeRemoteWorkStarts() {
        JobOrderMapper orders = mock(JobOrderMapper.class);
        when(orders.selectOne(any())).thenReturn(null);
        JobEvidenceService service = new JobEvidenceService(orders, mock(JobFileMapper.class));

        service.initializeOrders(99L, List.of(1L, 2L, 3L, 4L, 5L));

        verify(orders, times(5)).insert(argThat((JobOrderEntity row) -> "PENDING".equals(row.getStatus())));
    }

    @Test void storesOrderBusinessSnapshotOnTaskDetail() {
        JobOrderMapper orders = mock(JobOrderMapper.class);
        JobOrderEntity row = new JobOrderEntity(); row.setId(1L); row.setJobId(99L); row.setSourceOrderId(5L);
        when(orders.selectOne(any())).thenReturn(row);
        JobEvidenceService service = new JobEvidenceService(orders, mock(JobFileMapper.class));

        service.orderMetadata(99L, 5L, 88L, "20260702000001", "2026-07-02T13:14:13", "1040", "{\"orgName\":\"市场部\"}");

        verify(orders).updateById(argThat((JobOrderEntity saved) -> saved.getSnapshotId().equals(88L)
                && "20260702000001".equals(saved.getOrderCode())
                && saved.getOrderBusinessJson().contains("市场部")));
    }
}
