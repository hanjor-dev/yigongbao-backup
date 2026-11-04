package com.yigongbao.backup.backup;

/** 估算下载原文件、生成压缩包及原子发布临时副本所需的峰值工作空间。 */
public final class BackupWorkingSpaceEstimator {
    private BackupWorkingSpaceEstimator() { }

    public static long estimate(BackupManifest manifest, int reservationPercent) {
        if (manifest == null || manifest.assets() == null) throw new IllegalStateException("订单清单不存在");
        if (reservationPercent < 100) throw new IllegalArgumentException("工作空间预留比例不能小于100%");
        long total = 0;
        for (BackupManifest.Asset asset : manifest.assets()) {
            if (asset.size() < 0 || Long.MAX_VALUE - total < asset.size()) throw new IllegalStateException("订单清单容量溢出");
            total += asset.size();
        }
        try {
            return Math.max(1L, Math.addExact(Math.multiplyExact(total, reservationPercent), 99L) / 100L);
        } catch (ArithmeticException overflow) {
            throw new IllegalStateException("订单工作空间容量溢出", overflow);
        }
    }
}
