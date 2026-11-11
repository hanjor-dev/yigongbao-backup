package com.yigongbao.backup.storage.volume;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StorageVolumeAccessSqlPredicateTest {
    @Test
    void generatedSqlMatchesJavaDecisionsForEveryPersistedStateCombination() throws Exception {
        StorageVolumeAccessPolicy policy = new StorageVolumeAccessPolicy();
        Map<Long, Expected> expected = new LinkedHashMap<>();
        long id = 0;
        for (String tier : new String[]{"HOT", "COLD", "IMPORT"}) {
            for (String mount : new String[]{"ONLINE", "OFFLINE", "UNAVAILABLE"}) {
                for (String write : new String[]{"ACTIVE", "STANDBY", "READ_ONLY", "DISABLED"}) {
                    for (String admin : new String[]{"ENABLED", "RETIRED"}) {
                        for (String operation : new String[]{"IDLE", "SEALING", "ARCHIVE_GUARD", "GOING_OFFLINE", "MOUNTING", "REPAIR_REQUIRED"}) {
                            StorageVolumeEntity volume = volume(++id, tier, mount, write, admin, operation);
                            StorageVolumeAccessPolicy.ArchiveGuardContext guard = "ARCHIVE_GUARD".equals(operation)
                                    ? new StorageVolumeAccessPolicy.ArchiveGuardContext(id + 1000, id, "VERIFYING", true) : null;
                            volume.setOperationRefId(guard == null ? null : guard.archiveId());
                            expected.put(id, new Expected(volume, guard, policy.readable(volume, guard).allowed()));
                        }
                    }
                }
            }
        }

        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:volume_access;MODE=MySQL;DATABASE_TO_LOWER=TRUE")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE volume(id BIGINT PRIMARY KEY,tier VARCHAR(32),mount_status VARCHAR(32),write_status VARCHAR(32),admin_status VARCHAR(32),operation_status VARCHAR(32),operation_ref_id BIGINT,is_deleted INT)");
                statement.execute("CREATE TABLE archive(id BIGINT PRIMARY KEY,successor_volume_id BIGINT,phase VARCHAR(32),is_deleted INT)");
                for (Map.Entry<Long, Expected> entry : expected.entrySet()) {
                    StorageVolumeEntity volume = entry.getValue().volume();
                    statement.executeUpdate("INSERT INTO volume VALUES(" + volume.getId() + ",'" + volume.getTier() + "','" + volume.getMountStatus() + "','" + volume.getWriteStatus() + "','" + volume.getAdminStatus() + "','" + volume.getOperationStatus() + "'," + nullable(volume.getOperationRefId()) + ",0)");
                    if (entry.getValue().guard() != null) {
                        var guard = entry.getValue().guard();
                        statement.executeUpdate("INSERT INTO archive VALUES(" + guard.archiveId() + "," + guard.successorVolumeId() + ",'" + guard.phase() + "',0)");
                    }
                }
                String predicate = policy.readableSql(new StorageVolumeAccessSqlPredicate.SqlAliases("v", "a")).sql();
                ResultSet result = statement.executeQuery("SELECT v.id FROM volume v LEFT JOIN archive a ON a.id=v.operation_ref_id WHERE " + predicate);
                Map<Long, Boolean> actual = new LinkedHashMap<>();
                expected.keySet().forEach(key -> actual.put(key, false));
                while (result.next()) actual.put(result.getLong(1), true);
                expected.forEach((key, value) -> assertThat(actual.get(key)).as("volume " + key)
                        .isEqualTo(value.readable()));
            }
        }
    }

    private static String nullable(Long value) { return value == null ? "NULL" : value.toString(); }

    private static StorageVolumeEntity volume(long id, String tier, String mount, String write, String admin, String operation) {
        StorageVolumeEntity value = new StorageVolumeEntity();
        value.setId(id); value.setTier(tier); value.setMountStatus(mount); value.setWriteStatus(write);
        value.setAdminStatus(admin); value.setOperationStatus(operation); value.setIsDeleted(0);
        return value;
    }

    private record Expected(StorageVolumeEntity volume, StorageVolumeAccessPolicy.ArchiveGuardContext guard,
                            boolean readable) { }
}
