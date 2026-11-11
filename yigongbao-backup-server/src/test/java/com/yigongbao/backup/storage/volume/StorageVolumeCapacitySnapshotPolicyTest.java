package com.yigongbao.backup.storage.volume;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class StorageVolumeCapacitySnapshotPolicyTest {
    private static final LocalDateTime OBSERVED_AT = LocalDateTime.of(2026, 11, 11, 10, 30);

    @Test
    void successfulLegalProbeReplacesCapacityAndObservationTime() {
        StorageVolumeEntity volume = historicalVolume();
        StorageVolumeHealthService.HealthSnapshot snapshot =
                new StorageVolumeHealthService.HealthSnapshot(true, true, 1_000L, 250L, null, null, 4L);

        StorageVolumeCapacitySnapshotPolicy.apply(volume, snapshot, OBSERVED_AT);

        assertThat(volume.getLastProbedAt()).isEqualTo(OBSERVED_AT);
        assertThat(volume.getCapacityObservedAt()).isEqualTo(OBSERVED_AT);
        assertThat(volume.getTotalBytes()).isEqualTo(1_000L);
        assertThat(volume.getUsableBytes()).isEqualTo(250L);
        assertThat(volume.getLastErrorCode()).isNull();
        assertThat(volume.getLastErrorMessage()).isNull();
    }

    @Test
    void failedProbeKeepsLastSuccessfulCapacityAndOnlyUpdatesAttemptAndError() {
        StorageVolumeEntity volume = historicalVolume();
        StorageVolumeHealthService.HealthSnapshot snapshot =
                new StorageVolumeHealthService.HealthSnapshot(false, false, 0L, 0L,
                        "STORAGE_PROBE_FAILED", "disk offline", 8L);

        StorageVolumeCapacitySnapshotPolicy.apply(volume, snapshot, OBSERVED_AT);

        assertThat(volume.getLastProbedAt()).isEqualTo(OBSERVED_AT);
        assertThat(volume.getCapacityObservedAt()).isEqualTo(LocalDateTime.of(2026, 11, 10, 9, 0));
        assertThat(volume.getTotalBytes()).isEqualTo(500L);
        assertThat(volume.getUsableBytes()).isEqualTo(100L);
        assertThat(volume.getLastErrorCode()).isEqualTo("STORAGE_PROBE_FAILED");
        assertThat(volume.getLastErrorMessage()).isEqualTo("disk offline");
    }

    @Test
    void accessibleButIllegalCapacityDoesNotReplaceHistoricalSnapshot() {
        StorageVolumeEntity volume = historicalVolume();
        StorageVolumeHealthService.HealthSnapshot negative =
                new StorageVolumeHealthService.HealthSnapshot(true, true, 100L, -1L, null, null, 1L);
        StorageVolumeHealthService.HealthSnapshot tooLarge =
                new StorageVolumeHealthService.HealthSnapshot(true, true, 100L, 101L, null, null, 1L);

        StorageVolumeCapacitySnapshotPolicy.apply(volume, negative, OBSERVED_AT);
        assertThat(volume.getTotalBytes()).isEqualTo(500L);
        assertThat(volume.getCapacityObservedAt()).isEqualTo(LocalDateTime.of(2026, 11, 10, 9, 0));

        StorageVolumeCapacitySnapshotPolicy.apply(volume, tooLarge, OBSERVED_AT.plusMinutes(1));
        assertThat(volume.getUsableBytes()).isEqualTo(100L);
        assertThat(volume.getCapacityObservedAt()).isEqualTo(LocalDateTime.of(2026, 11, 10, 9, 0));
        assertThat(volume.getLastErrorCode()).isEqualTo("INVALID_CAPACITY_SNAPSHOT");
    }

    @Test
    void usedPercentRoundsToOneDecimalWithoutOverflow() {
        assertThat(StorageVolumeCapacitySnapshotPolicy.usedPercent(3L, 1L)).isEqualByComparingTo("66.7");
        assertThat(StorageVolumeCapacitySnapshotPolicy.usedPercent(Long.MAX_VALUE, 0L))
                .isEqualByComparingTo("100.0");
        assertThat(StorageVolumeCapacitySnapshotPolicy.usedPercent(0L, 0L)).isNull();
        assertThat(StorageVolumeCapacitySnapshotPolicy.usedPercent(100L, 101L)).isNull();
    }

    private StorageVolumeEntity historicalVolume() {
        StorageVolumeEntity volume = new StorageVolumeEntity();
        volume.setTier("COLD");
        volume.setMountStatus("OFFLINE");
        volume.setTotalBytes(500L);
        volume.setUsableBytes(100L);
        volume.setLastProbedAt(LocalDateTime.of(2026, 11, 10, 9, 0));
        volume.setCapacityObservedAt(LocalDateTime.of(2026, 11, 10, 9, 0));
        return volume;
    }
}
