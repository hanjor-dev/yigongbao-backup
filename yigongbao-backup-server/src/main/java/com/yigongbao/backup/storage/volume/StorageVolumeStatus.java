package com.yigongbao.backup.storage.volume;

/** 存储卷的分层、挂载与写入状态。 */
public final class StorageVolumeStatus {
    private StorageVolumeStatus() {
    }

    public enum Tier { HOT, COLD, IMPORT }

    public enum Mount { ONLINE, OFFLINE, UNAVAILABLE }

    public enum Write { ACTIVE, STANDBY, READ_ONLY, DISABLED }

    public enum Admin { ENABLED, RETIRED }

    public enum Operation { IDLE, SEALING, ARCHIVE_GUARD, GOING_OFFLINE, MOUNTING, REPAIR_REQUIRED }
}
