package com.yigongbao.backup.storage.volume;

import java.nio.file.Path;

/** 未纳管挂载点的只读发现结果。 */
public record DiscoveredStorageVolume(Path mountPath, Path rootPath, StorageAccessType accessType,
                                      String fileStoreName, String fileStoreType, String volumeSerial,
                                      long totalBytes, long usableBytes, long usedBytes) {
    public DiscoveredStorageVolume(Path mountPath, Path rootPath, StorageAccessType accessType,
                                   String fileStoreName, String fileStoreType,
                                   long totalBytes, long usableBytes, long usedBytes) {
        this(mountPath, rootPath, accessType, fileStoreName, fileStoreType, "TEST:"+fileStoreName, totalBytes, usableBytes, usedBytes);
    }
    public DiscoveredStorageVolume(Path mountPath, Path rootPath, StorageAccessType accessType,
                                   String fileStoreName, String fileStoreType) {
        this(mountPath, rootPath, accessType, fileStoreName, fileStoreType, "TEST:"+fileStoreName, 0L, 0L, 0L);
    }
}
