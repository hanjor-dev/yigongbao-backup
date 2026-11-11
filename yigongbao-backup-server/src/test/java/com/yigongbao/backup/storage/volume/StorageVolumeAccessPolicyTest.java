package com.yigongbao.backup.storage.volume;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StorageVolumeAccessPolicyTest {
    private final StorageVolumeAccessPolicy policy = new StorageVolumeAccessPolicy();

    @Test
    void appliesTheCompleteReadAndWriteStateMatrix() {
        List<Case> cases = List.of(
                new Case("idle active hot", volume("HOT", "ONLINE", "ACTIVE", "ENABLED", "IDLE"), null, true, true),
                new Case("idle standby hot", volume("HOT", "ONLINE", "STANDBY", "ENABLED", "IDLE"), null, true, false),
                new Case("idle readonly hot", volume("HOT", "ONLINE", "READ_ONLY", "ENABLED", "IDLE"), null, true, false),
                new Case("online cold", volume("COLD", "ONLINE", "READ_ONLY", "ENABLED", "IDLE"), null, true, false),
                new Case("offline cold", volume("COLD", "OFFLINE", "READ_ONLY", "ENABLED", "IDLE"), null, false, false),
                new Case("import", volume("IMPORT", "ONLINE", "READ_ONLY", "ENABLED", "IDLE"), null, false, false),
                new Case("unavailable", volume("HOT", "UNAVAILABLE", "ACTIVE", "ENABLED", "IDLE"), null, false, false),
                new Case("retired", volume("HOT", "ONLINE", "ACTIVE", "RETIRED", "IDLE"), null, false, false),
                new Case("disabled", volume("HOT", "ONLINE", "DISABLED", "ENABLED", "IDLE"), null, false, false),
                new Case("sealing", volume("HOT", "ONLINE", "ACTIVE", "ENABLED", "SEALING"), null, false, false),
                new Case("going offline", volume("HOT", "ONLINE", "ACTIVE", "ENABLED", "GOING_OFFLINE"), null, false, false),
                new Case("mounting", volume("HOT", "ONLINE", "ACTIVE", "ENABLED", "MOUNTING"), null, false, false),
                new Case("repair", volume("HOT", "ONLINE", "ACTIVE", "ENABLED", "REPAIR_REQUIRED"), null, false, false),
                new Case("deleted", deleted(volume("HOT", "ONLINE", "ACTIVE", "ENABLED", "IDLE")), null, false, false),
                new Case("valid guard", guarded(), guard(91L, 7L, "VERIFYING", true), true, true),
                new Case("terminal guard", guarded(), guard(91L, 7L, "ARCHIVED", false), false, false),
                new Case("wrong archive guard", guarded(), guard(92L, 7L, "VERIFYING", true), false, false),
                new Case("wrong successor guard", guarded(), guard(91L, 8L, "VERIFYING", true), false, false)
        );

        for (Case testCase : cases) {
            assertThat(policy.readable(testCase.volume(), testCase.guard()).allowed())
                    .as(testCase.name() + " readable").isEqualTo(testCase.readable());
            assertThat(policy.writable(testCase.volume(), testCase.guard()).allowed())
                    .as(testCase.name() + " writable").isEqualTo(testCase.writable());
        }
    }

    @Test
    void distinguishesExpectedOfflineAndStandbyReadiness() {
        StorageVolumeEntity cold = volume("COLD", "OFFLINE", "READ_ONLY", "ENABLED", "IDLE");
        assertThat(policy.expectedOffline(cold)).isTrue();
        cold.setOperationStatus("REPAIR_REQUIRED");
        assertThat(policy.expectedOffline(cold)).isFalse();

        StorageVolumeEntity standby = volume("HOT", "ONLINE", "STANDBY", "ENABLED", "IDLE");
        standby.setTotalBytes(100L);
        standby.setUsableBytes(11L);
        assertThat(policy.standbyReady(standby, true, 90)).isTrue();
        standby.setUsableBytes(10L);
        assertThat(policy.standbyReady(standby, true, 90)).isFalse();
        assertThat(policy.standbyReady(standby, false, 90)).isFalse();
    }

    private static StorageVolumeEntity guarded() {
        StorageVolumeEntity value = volume("HOT", "ONLINE", "ACTIVE", "ENABLED", "ARCHIVE_GUARD");
        value.setId(7L);
        value.setOperationRefId(91L);
        return value;
    }

    private static StorageVolumeAccessPolicy.ArchiveGuardContext guard(Long archiveId, Long successorId,
                                                                        String phase, boolean active) {
        return new StorageVolumeAccessPolicy.ArchiveGuardContext(archiveId, successorId, phase, active);
    }

    private static StorageVolumeEntity deleted(StorageVolumeEntity value) {
        value.setIsDeleted(1);
        return value;
    }

    private static StorageVolumeEntity volume(String tier, String mount, String write, String admin, String operation) {
        StorageVolumeEntity value = new StorageVolumeEntity();
        value.setId(1L);
        value.setTier(tier);
        value.setMountStatus(mount);
        value.setWriteStatus(write);
        value.setAdminStatus(admin);
        value.setOperationStatus(operation);
        value.setIsDeleted(0);
        return value;
    }

    private record Case(String name, StorageVolumeEntity volume,
                        StorageVolumeAccessPolicy.ArchiveGuardContext guard,
                        boolean readable, boolean writable) { }
}
