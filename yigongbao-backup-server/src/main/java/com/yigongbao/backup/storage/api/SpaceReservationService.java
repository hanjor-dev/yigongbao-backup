package com.yigongbao.backup.storage.api;

/** 可持久化空间预留服务接口。 */
public interface SpaceReservationService extends SpacePlanner {
    /** 为指定持久化任务预留空间，用于隔离同一容量域中的并发长任务。 */
    default SpaceReservation reserve(String capacityDomain, long bytes, long jobId) {
        return reserve(capacityDomain, bytes);
    }

    /**
     * 为指定存储卷预留空间。实现必须根据卷绑定的容量域探测，不能再回退到全局热备目录。
     */
    default SpaceReservation reserve(long storageVolumeId, long bytes, long jobId) {
        return reserve("volume:" + storageVolumeId, bytes, jobId);
    }
}
