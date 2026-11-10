package com.yigongbao.backup.storage.reservation;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** 让长任务的空间预留跟随任务执行租约存活，避免固定两小时后容量保护失效。 */
@Component
@RequiredArgsConstructor
public class SpaceReservationLeaseScheduler {
    private static final long RENEWED_LEASE_MINUTES = 5;
    private final SpaceReservationMapper reservations;

    @Scheduled(fixedDelay = 30_000)
    public void renewActiveReservations() {
        renew(LocalDateTime.now());
    }

    public int renew(LocalDateTime now) {
        if (now == null) throw new IllegalArgumentException("续租时间不能为空");
        return reservations.renewForRunningJobs(now, now.plusMinutes(RENEWED_LEASE_MINUTES));
    }
}
