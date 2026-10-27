package com.yigongbao.backup.storage.api;

/** 存储容量预留规划接口。 */
public interface SpacePlanner {
    SpaceReservation reserve(String capacityDomain, long bytes);
    void release(SpaceReservation reservation);
    record SpaceReservation(String capacityDomain, long bytes, String token) {}
}
