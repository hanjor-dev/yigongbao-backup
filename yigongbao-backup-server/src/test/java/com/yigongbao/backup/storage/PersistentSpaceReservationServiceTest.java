package com.yigongbao.backup.storage;

import com.yigongbao.backup.storage.reservation.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class PersistentSpaceReservationServiceTest {
    @Test
    void reservationUsesOwningJobId() {
        StoragePoolMapper pools = mock(StoragePoolMapper.class);
        SpaceReservationMapper rows = mock(SpaceReservationMapper.class);
        StoragePoolEntity pool = new StoragePoolEntity();
        pool.setId(7L); pool.setAvailableBytes(1000L);
        when(pools.selectOne(any())).thenReturn(pool);
        when(pools.selectForUpdate(7L)).thenReturn(pool);
        when(rows.sumActive(7L)).thenReturn(0L);

        new PersistentSpaceReservationService(pools, rows).reserve("hot", 100L, 42L);

        ArgumentCaptor<SpaceReservationEntity> saved = ArgumentCaptor.forClass(SpaceReservationEntity.class);
        verify(rows).insert(saved.capture());
        assertEquals(42L, saved.getValue().getJobId());
    }
}
