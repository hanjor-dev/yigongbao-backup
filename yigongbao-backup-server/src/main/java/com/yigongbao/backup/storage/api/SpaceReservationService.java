package com.yigongbao.backup.storage.api;

/** 可持久化空间预留服务接口。 */
public interface SpaceReservationService extends SpacePlanner {
    /** 为指定持久化任务预留空间，用于隔离同一容量域中的并发长任务。 */
    default SpaceReservation reserve(String capacityDomain, long bytes, long jobId) {
        return reserve(capacityDomain, bytes);
    }
}
