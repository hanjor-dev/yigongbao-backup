package com.yigongbao.backup.storage.api;

import java.nio.file.Path;

/** 存储池容量和容量域探测接口。 */
public interface StorageProbe {
    StorageSnapshot probe(Path path);
    record StorageSnapshot(String capacityDomain, Path path, long totalBytes, long usableBytes, boolean capacityKnown) {
        public long usedBytes() { return capacityKnown ? Math.max(0, totalBytes - usableBytes) : -1; }
    }
}
