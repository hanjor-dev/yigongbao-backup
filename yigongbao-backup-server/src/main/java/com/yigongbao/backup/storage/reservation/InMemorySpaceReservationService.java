package com.yigongbao.backup.storage.reservation;

import com.yigongbao.backup.storage.api.SpaceReservationService;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** 预留账本；生产环境由 bk_storage_reservation 持久化并用条件更新实现同一语义。 */
public final class InMemorySpaceReservationService implements SpaceReservationService {
    private final Map<String, Long> capacities = new ConcurrentHashMap<>();
    private final Map<String, SpaceReservation> active = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> reserved = new ConcurrentHashMap<>();
    public void register(String domain, long capacity) { capacities.put(domain, capacity); reserved.putIfAbsent(domain, new AtomicLong()); }
    public synchronized SpaceReservation reserve(String domain, long bytes) {
        if (bytes < 0) throw new IllegalArgumentException("预留空间不能为负");
        Long capacity = capacities.get(domain); if (capacity == null || capacity < 0) throw new IllegalStateException("容量未知，禁止启动大任务");
        AtomicLong used = reserved.get(domain); if (used.get() > capacity - bytes) throw new IllegalStateException("可用空间不足");
        used.addAndGet(bytes); SpaceReservation r = new SpaceReservation(domain, bytes, UUID.randomUUID().toString()); active.put(r.token(), r); return r;
    }
    public synchronized void release(SpaceReservation reservation) { if (active.remove(reservation.token()) != null) reserved.get(reservation.capacityDomain()).addAndGet(-reservation.bytes()); }
}
