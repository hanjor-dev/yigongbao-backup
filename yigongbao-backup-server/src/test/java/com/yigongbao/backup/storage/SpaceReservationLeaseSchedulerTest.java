package com.yigongbao.backup.storage;

import com.yigongbao.backup.storage.reservation.SpaceReservationLeaseScheduler;
import com.yigongbao.backup.storage.reservation.SpaceReservationMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SpaceReservationLeaseSchedulerTest {

    @Test
    void extendsReservationsOwnedByJobsWithLiveLeases() {
        SpaceReservationMapper reservations = mock(SpaceReservationMapper.class);
        LocalDateTime now = LocalDateTime.of(2026, 11, 10, 20, 0);
        when(reservations.renewForRunningJobs(now, now.plusMinutes(5))).thenReturn(2);

        int renewed = new SpaceReservationLeaseScheduler(reservations).renew(now);

        assertThat(renewed).isEqualTo(2);
        verify(reservations).renewForRunningJobs(now, now.plusMinutes(5));
    }
}
