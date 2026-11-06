package com.yigongbao.backup.storage.api;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.stream.Stream;

/** 按逻辑存储卷路由的文件访问接口。 */
public interface VolumeArtifactStore {
    Path put(long storageVolumeId, String storageKey, InputStream content) throws IOException;
    InputStream open(long storageVolumeId, String storageKey) throws IOException;
    Stream<Path> list(long storageVolumeId, String storageKey) throws IOException;
    void move(long storageVolumeId, String sourceStorageKey, String targetStorageKey) throws IOException;
    void delete(long storageVolumeId, String storageKey) throws IOException;
    Path resolve(long storageVolumeId, String storageKey) throws IOException;
}
