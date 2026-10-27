package com.yigongbao.backup.order;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ColdSupplementCoverageServiceTest {
    @Test
    void marksAllSupplementsOfOrdersAsCoveredByBatch() {
        OrderSnapshotMapper snapshots = mock(OrderSnapshotMapper.class);
        BkSupplementFileMapper supplements = mock(BkSupplementFileMapper.class);
        OrderSnapshotEntity snapshot = new OrderSnapshotEntity(); snapshot.setId(7L); snapshot.setOrderCode("order-1");
        when(snapshots.selectList(any())).thenReturn(List.of(snapshot));
        when(supplements.update(any(), any())).thenReturn(2);

        int changed = new ColdSupplementCoverageService(snapshots, supplements)
                .markCovered(9L, List.of("order-1"), 3L);

        assertThat(changed).isEqualTo(2);
        verify(supplements).update(any(), any());
    }
}
