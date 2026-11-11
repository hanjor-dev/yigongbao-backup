package com.yigongbao.backup.storage.volume;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/** 统一维护最近探测尝试与最后一次合法成功容量快照。 */
public final class StorageVolumeCapacitySnapshotPolicy {
    private static final String INVALID_CAPACITY = "INVALID_CAPACITY_SNAPSHOT";

    private StorageVolumeCapacitySnapshotPolicy() {
    }

    public static void apply(StorageVolumeEntity volume,
                             StorageVolumeHealthService.HealthSnapshot snapshot,
                             LocalDateTime observedAt) {
        if (volume == null || snapshot == null || observedAt == null) {
            throw new IllegalArgumentException("容量快照参数不能为空");
        }
        volume.setLastProbedAt(observedAt);
        if (legal(snapshot)) {
            volume.setTotalBytes(snapshot.totalBytes());
            volume.setUsableBytes(snapshot.usableBytes());
            volume.setCapacityObservedAt(observedAt);
            volume.setLastErrorCode(null);
            volume.setLastErrorMessage(null);
            return;
        }
        if (snapshot.accessible()) {
            volume.setLastErrorCode(INVALID_CAPACITY);
            volume.setLastErrorMessage("容量快照非法，已保留最近一次合法容量");
        } else {
            volume.setLastErrorCode(snapshot.errorCode());
            volume.setLastErrorMessage(snapshot.errorMessage());
        }
    }

    public static boolean legal(StorageVolumeHealthService.HealthSnapshot snapshot) {
        return snapshot != null && snapshot.accessible() && snapshot.totalBytes() > 0
                && snapshot.usableBytes() >= 0 && snapshot.usableBytes() <= snapshot.totalBytes();
    }

    public static BigDecimal usedPercent(Long totalBytes, Long usableBytes) {
        if (totalBytes == null || usableBytes == null || totalBytes <= 0
                || usableBytes < 0 || usableBytes > totalBytes) {
            return null;
        }
        return BigDecimal.valueOf(totalBytes - usableBytes)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(totalBytes), 1, RoundingMode.HALF_UP);
    }
}
