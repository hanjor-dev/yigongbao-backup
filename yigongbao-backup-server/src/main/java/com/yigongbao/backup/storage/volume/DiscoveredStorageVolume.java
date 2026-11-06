package com.yigongbao.backup.storage.volume;

import java.nio.file.Path;

/** 未纳管挂载点的只读发现结果。 */
public record DiscoveredStorageVolume(Path mountPath, Path rootPath, StorageAccessType accessType,
                                      String fileStoreName, String fileStoreType) {
}
