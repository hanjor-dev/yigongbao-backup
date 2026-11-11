package com.yigongbao.backup.storage.volume;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Function;

import static com.yigongbao.backup.storage.volume.StorageVolumeAccessSqlPredicate.SqlAliases;

/**
 * 存储卷访问真值表的唯一实现。
 * Java 判定与 SQL 谓词逐条复用 {@link Rule}，调用方不得再复制状态组合。
 */
@Component
public final class StorageVolumeAccessPolicy {
    private static final Set<String> ACTIVE_ARCHIVE_PHASES = Set.of(
            "REQUESTED", "SWITCHED", "MANIFEST_FROZEN", "VERIFYING", "COMMITTING");

    private static final List<Rule> READ_RULES = List.of(
            new Rule("STORAGE_VOLUME_MISSING", "存储卷不存在或已删除",
                    (volume, guard) -> volume != null && !Integer.valueOf(1).equals(volume.getIsDeleted()),
                    aliases -> aliases.volume() + ".is_deleted=0"),
            new Rule("STORAGE_VOLUME_NOT_ONLINE", "存储卷当前不在线",
                    (volume, guard) -> eq(volume.getMountStatus(), "ONLINE"),
                    aliases -> aliases.volume() + ".mount_status='ONLINE'"),
            new Rule("STORAGE_VOLUME_RETIRED", "存储卷已退役",
                    (volume, guard) -> volume.getAdminStatus() == null || eq(volume.getAdminStatus(), "ENABLED"),
                    aliases -> "COALESCE(" + aliases.volume() + ".admin_status,'ENABLED')='ENABLED'"),
            new Rule("STORAGE_VOLUME_DISABLED", "存储卷已停用",
                    (volume, guard) -> !eq(volume.getWriteStatus(), "DISABLED"),
                    aliases -> aliases.volume() + ".write_status<>'DISABLED'"),
            new Rule("STORAGE_VOLUME_TIER_UNREADABLE", "存储卷层级或写状态不支持读取",
                    (volume, guard) -> eq(volume.getTier(), "HOT")
                            || (eq(volume.getTier(), "COLD") && eq(volume.getWriteStatus(), "READ_ONLY")),
                    aliases -> "(" + aliases.volume() + ".tier='HOT' OR (" + aliases.volume()
                            + ".tier='COLD' AND " + aliases.volume() + ".write_status='READ_ONLY'))"),
            new Rule("STORAGE_VOLUME_OPERATION_BUSY", "存储卷正在执行不允许访问的生命周期操作",
                    (volume, guard) -> idle(volume) || validArchiveGuard(volume, guard),
                    StorageVolumeAccessPolicy::operationSql)
    );

    private static final List<Rule> WRITE_RULES = List.of(
            READ_RULES.get(0), READ_RULES.get(1), READ_RULES.get(2),
            new Rule("STORAGE_VOLUME_NOT_ACTIVE", "目标卷已不再处于活动热备写入状态",
                    (volume, guard) -> eq(volume.getTier(), "HOT") && eq(volume.getWriteStatus(), "ACTIVE"),
                    aliases -> "(" + aliases.volume() + ".tier='HOT' AND " + aliases.volume()
                            + ".write_status='ACTIVE')"),
            READ_RULES.get(5)
    );

    public AccessDecision readable(StorageVolumeEntity volume, ArchiveGuardContext guard) {
        return decide(READ_RULES, volume, guard);
    }

    public AccessDecision writable(StorageVolumeEntity volume, ArchiveGuardContext guard) {
        return decide(WRITE_RULES, volume, guard);
    }

    public boolean standbyReady(StorageVolumeEntity volume, boolean capacityFresh, int threshold) {
        if (volume == null || !capacityFresh || threshold < 1 || threshold > 100) return false;
        if (Integer.valueOf(1).equals(volume.getIsDeleted())
                || !eq(volume.getTier(), "HOT") || !eq(volume.getMountStatus(), "ONLINE")
                || !eq(volume.getWriteStatus(), "STANDBY")
                || !(volume.getAdminStatus() == null || eq(volume.getAdminStatus(), "ENABLED"))
                || !idle(volume)) return false;
        BigDecimal used = StorageVolumeCapacitySnapshotPolicy.usedPercent(volume.getTotalBytes(), volume.getUsableBytes());
        return used != null && used.compareTo(BigDecimal.valueOf(threshold)) < 0;
    }

    public boolean expectedOffline(StorageVolumeEntity volume) {
        return volume != null && !Integer.valueOf(1).equals(volume.getIsDeleted())
                && eq(volume.getTier(), "COLD") && eq(volume.getMountStatus(), "OFFLINE")
                && eq(volume.getWriteStatus(), "READ_ONLY")
                && (volume.getAdminStatus() == null || eq(volume.getAdminStatus(), "ENABLED"))
                && idle(volume);
    }

    public StorageVolumeAccessSqlPredicate readableSql(SqlAliases aliases) {
        return new StorageVolumeAccessSqlPredicate(READ_RULES.stream().map(rule -> "(" + rule.sql().apply(aliases) + ")")
                .reduce((left, right) -> left + " AND " + right).orElseThrow());
    }

    public StorageVolumeAccessSqlPredicate expectedOfflineSql(SqlAliases aliases) {
        String v=aliases.volume();
        return new StorageVolumeAccessSqlPredicate(v+".is_deleted=0 AND "+v+".tier='COLD' AND "+v
                +".mount_status='OFFLINE' AND "+v+".write_status='READ_ONLY' AND COALESCE("+v
                +".admin_status,'ENABLED')='ENABLED' AND COALESCE("+v+".operation_status,'IDLE')='IDLE'");
    }

    public ArchiveGuardContext archiveGuard(StorageVolumeEntity volume, Long archiveId, Long successorVolumeId,
                                             String phase, boolean active) {
        if (volume == null || !eq(volume.getOperationStatus(), "ARCHIVE_GUARD")) return null;
        return new ArchiveGuardContext(archiveId, successorVolumeId, phase, active);
    }

    private static AccessDecision decide(List<Rule> rules, StorageVolumeEntity volume, ArchiveGuardContext guard) {
        for (Rule rule : rules) {
            if (!rule.java().test(volume, guard)) return new AccessDecision(false, rule.code(), rule.message());
        }
        return new AccessDecision(true, "ALLOWED", "允许访问");
    }

    private static boolean idle(StorageVolumeEntity volume) {
        return volume != null && (volume.getOperationStatus() == null || eq(volume.getOperationStatus(), "IDLE"));
    }

    private static boolean validArchiveGuard(StorageVolumeEntity volume, ArchiveGuardContext guard) {
        return volume != null && guard != null && guard.active()
                && eq(volume.getTier(), "HOT") && eq(volume.getMountStatus(), "ONLINE")
                && eq(volume.getWriteStatus(), "ACTIVE") && eq(volume.getOperationStatus(), "ARCHIVE_GUARD")
                && volume.getId() != null && volume.getId().equals(guard.successorVolumeId())
                && volume.getOperationRefId() != null && volume.getOperationRefId().equals(guard.archiveId())
                && ACTIVE_ARCHIVE_PHASES.contains(guard.phase());
    }

    private static String operationSql(SqlAliases aliases) {
        String volume = aliases.volume();
        String archive = aliases.archive();
        String phases = ACTIVE_ARCHIVE_PHASES.stream().sorted().map(value -> "'" + value + "'")
                .reduce((left, right) -> left + "," + right).orElseThrow();
        return "(COALESCE(" + volume + ".operation_status,'IDLE')='IDLE' OR ("
                + volume + ".operation_status='ARCHIVE_GUARD' AND " + volume + ".tier='HOT' AND "
                + volume + ".mount_status='ONLINE' AND " + volume + ".write_status='ACTIVE' AND "
                + volume + ".operation_ref_id=" + archive + ".id AND " + archive + ".successor_volume_id="
                + volume + ".id AND " + archive + ".is_deleted=0 AND " + archive + ".phase IN (" + phases + ")))";
    }

    private static boolean eq(String actual, String expected) { return expected.equals(actual); }

    public record ArchiveGuardContext(Long archiveId, Long successorVolumeId, String phase, boolean active) { }

    public record AccessDecision(boolean allowed, String code, String message) { }

    private record Rule(String code, String message,
                        BiPredicate<StorageVolumeEntity, ArchiveGuardContext> java,
                        Function<SqlAliases, String> sql) { }
}
